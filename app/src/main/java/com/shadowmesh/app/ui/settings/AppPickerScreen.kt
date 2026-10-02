package com.shadowmesh.app.ui.settings

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.shadowmesh.app.VPNUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.shadowmesh.SplitTunnelConfig
import uniffi.shadowmesh.SplitTunnelMode

data class AppInfoData(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val applicationInfo: ApplicationInfo,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(
    uiState: VPNUiState,
    currentConfig: SplitTunnelConfig,
    onSaveConfig: (SplitTunnelConfig) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val themeColor = Color(color = uiState.themeColor)
    var allInstalledApps by remember { mutableStateOf(value = emptyList<AppInfoData>()) }
    var isLoading by remember { mutableStateOf(value = true) }
    var searchQuery by remember { mutableStateOf(value = "") }
    // Preinstalled apps are shown by DEFAULT. They are the overwhelming majority
    // of apps on ColorOS/OriginOS, so hiding them made the picker look empty.
    var showSystemApps by remember { mutableStateOf(value = true) }
    var enumerationError by remember { mutableStateOf(value = false) }

    var selectedApps by remember { mutableStateOf(currentConfig.appList.toSet()) }
    var isEnabled by remember { mutableStateOf(currentConfig.enabled) }
    var mode by remember { mutableStateOf(currentConfig.mode) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // A launcher intent tells us the user can actually see and start the
            // app. Enumerating every installed package also pulls in background
            // services and system components, which are noise in a picker.
            val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)

            // Staged counters. The picker rendered empty with no exception and
            // no error log, which means the failure happens after a *successful*
            // enumeration. These identify which stage drops the list, instead of
            // leaving the next person to guess.
                    android.util.Log.i(
                        "ShadowMeshAppPicker",
                        "stage=query installed=${runCatching { packageManager.getInstalledApplications(PackageManager.GET_META_DATA).size }.getOrDefault(-1)}"
                    )

            val apps =
                try {
                    // Primary path: the full installed-application list.
                    //
                    // Package visibility on Android 11+ filters this on some OEM
                    // builds even with QUERY_ALL_PACKAGES granted, and the
                    // picker then renders an empty list with no error - which is
                    // indistinguishable from having no apps installed. Rather
                    // than depend on that path alone, the result is cross-checked
                    // against LauncherApps and the two are merged.
                    //
                    // LauncherApps is the API a launcher would use for exactly
                    // this, and it is not subject to the same package-visibility
                    // filtering, so it is the reliable source here. A split
                    // tunnel picker only ever wants launchable apps anyway, so
                    // nothing is lost by preferring it.
                    val installed = runCatching {
                        packageManager
                            .getInstalledApplications(PackageManager.GET_META_DATA)
                    }.getOrDefault(emptyList())

                    val byPackage = installed.associateBy { it.packageName }

                    val launcherApps = runCatching {
                        val launcher = androidx.core.content.ContextCompat.getSystemService(
                            context,
                            android.content.pm.LauncherApps::class.java
                        )
                        val user = android.os.Process.myUserHandle()
                        launcher?.getActivityList(null, user)?.associate {
                            it.applicationInfo.packageName to it.applicationInfo
                        }.orEmpty()
                    }.getOrDefault(emptyMap())

                    android.util.Log.i(
                        "ShadowMeshAppPicker",
                        "stage=sources installed=${installed.size} launcher=${launcherApps.size}"
                    )

                    (byPackage.keys + launcherApps.keys)
                        .mapNotNull { packageName ->
                            val info = launcherApps[packageName] ?: byPackage[packageName]
                                ?: return@mapNotNull null
                            AppInfoData(
                                packageName = packageName,
                                label = runCatching {
                                    packageManager.getApplicationLabel(info).toString()
                                }.getOrDefault(packageName),
                                isSystem = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                                applicationInfo = info,
                            )
                        }
                        // A split-tunnel picker only lists apps the user can
                        // actually launch. launcherApps is launchable by
                        // construction; installed entries need the check, and
                        // it is now the only filter, so a failure here empties
                        // the list rather than removing one bad row.
                        .filter {
                            launcherApps.containsKey(it.packageName) ||
                                packageManager.getLaunchIntentForPackage(it.packageName) != null
                        }
                        .sortedBy { it.label.lowercase() }
                        .also {
                            android.util.Log.i(
                                "ShadowMeshAppPicker",
                                "stage=after-enumeration kept=${it.size}"
                            )
                        }
                        .toList()
                } catch (e: Exception) {
                    // Previously this returned an empty list silently, which is
                    // indistinguishable from "you have no apps installed" and is
                    // exactly how the empty-picker bug stayed undiagnosed.
                    android.util.Log.e("ShadowMeshAppPicker", "Failed to enumerate installed applications", e)
                    enumerationError = true
                    emptyList()
                }

            withContext(Dispatchers.Main) {
                allInstalledApps = apps
                isLoading = false
            }
        }
    }

    val (selectedList, unselectedList) =
        remember(allInstalledApps, selectedApps, searchQuery, showSystemApps) {
            val candidates =
                allInstalledApps.map {
                    SplitTunnelCandidate(
                        packageName = it.packageName,
                        label = it.label,
                        // FLAG_SYSTEM marks preinstallation, not privilege.
                        isPreinstalled = it.isSystem,
                        hasLauncher = true,
                    )
                }
            // Eligibility (own package / non-launchable) is applied first, then
            // the search. The preinstalled toggle is applied ONLY to the
            // unselected pool: an app the user has already selected must stay
            // visible so it can be deselected, otherwise it remains applied to
            // the tunnel while being impossible to see or remove.
            val eligible = SplitTunnelAppPolicy.search(
                SplitTunnelAppPolicy.visibleApps(
                    apps = candidates,
                    includePreinstalled = true,
                    selfPackage = context.packageName,
                ),
                query = searchQuery,
            )
            android.util.Log.i(
                "ShadowMeshAppPicker",
                "stage=after-policy eligible=${eligible.size} raw=${allInstalledApps.size} query='$searchQuery' preinstalled=${allInstalledApps.count { it.isSystem }}"
            )
            val selected = eligible.filter { selectedApps.contains(it.packageName) }
            val unselectedPool =
                if (showSystemApps) eligible else eligible.filterNot { it.isPreinstalled }
            val unselected = unselectedPool.filterNot { selectedApps.contains(it.packageName) }

            val byPackage = allInstalledApps.associateBy { it.packageName }
            (selected + unselected).mapNotNull { byPackage[it.packageName] }
                .let { visible ->
                    visible.filter { selectedApps.contains(it.packageName) } to
                        visible.filterNot { selectedApps.contains(it.packageName) }
                }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Split Tunneling", color = Color.White) },
                actions = {
                    TextButton(onClick = {
                        onSaveConfig(SplitTunnelConfig(isEnabled, mode, selectedApps.toList()))
                        onBack()
                    }) {
                        Text("APPLY", color = themeColor, fontWeight = FontWeight.Black)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = Color.Transparent,
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).padding(horizontal = 16.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("Enable Split Tunneling", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Route specific apps through the VPN", color = Color.Gray, fontSize = 12.sp)
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { isEnabled = it },
                            colors =
                                SwitchDefaults.colors(
                                    checkedThumbColor = themeColor,
                                    checkedTrackColor = themeColor.copy(alpha = 0.5f),
                                ),
                        )
                    }

                    if (isEnabled) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = mode == SplitTunnelMode.EXCLUDE,
                                onClick = { mode = SplitTunnelMode.EXCLUDE },
                                label = { Text("Exclude mode") },
                                colors =
                                    FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = themeColor,
                                        selectedLabelColor = Color.White,
                                        containerColor = Color.White.copy(alpha = 0.05f),
                                        labelColor = Color.Gray,
                                    ),
                            )
                            FilterChip(
                                selected = mode == SplitTunnelMode.INCLUDE,
                                onClick = { mode = SplitTunnelMode.INCLUDE },
                                label = { Text("Include mode") },
                                colors =
                                    FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF10B981),
                                        selectedLabelColor = Color.White,
                                        containerColor = Color.White.copy(alpha = 0.05f),
                                        labelColor = Color.Gray,
                                    ),
                            )
                        }

                        // Dynamic Summary Text
                        val summaryText =
                            if (mode == SplitTunnelMode.EXCLUDE) {
                                "The ${selectedApps.size} selected apps will BYPASS the VPN tunnel."
                            } else {
                                "ONLY the ${selectedApps.size} selected apps will use the VPN tunnel."
                            }
                        Text(
                            text = summaryText,
                            color = if (mode == SplitTunnelMode.EXCLUDE) Color(0xFFF59E0B) else Color(0xFF10B981),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            if (isEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search apps") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                        modifier = Modifier.weight(1f),
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = Color.White.copy(alpha = 0.05f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                                focusedIndicatorColor = themeColor,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                            ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                    )

                    IconButton(
                        onClick = { showSystemApps = !showSystemApps },
                        modifier =
                            Modifier.background(
                                if (showSystemApps) themeColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                                RoundedCornerShape(12.dp),
                            ),
                    ) {
                        Icon(
                            Icons.Default.SettingsSystemDaydream,
                            contentDescription = "Show System Apps",
                            tint = if (showSystemApps) themeColor else Color.Gray,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = themeColor)
                    }
                } else if (enumerationError) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Could not read installed apps",
                                color = Color(0xFFF87171),
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "The package list was unavailable. Check that ShadowMesh has permission to view installed apps, then reopen this screen. See logcat tag ShadowMeshAppPicker for details.",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                } else if (selectedList.isEmpty() && unselectedList.isEmpty() && searchQuery.isNotBlank()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No apps match \"$searchQuery\"", color = Color.Gray, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (selectedList.isNotEmpty()) {
                            item {
                                Text(
                                    "SELECTED APPS (${selectedList.size})",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.5.sp,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                            items(selectedList, key = { it.packageName }) { appInfo ->
                                AppRow(
                                    appInfo = appInfo,
                                    packageManager = packageManager,
                                    isSelected = true,
                                    themeColor = themeColor,
                                    onToggle = { checked ->
                                        selectedApps =
                                            if (checked) {
                                                selectedApps + appInfo.packageName
                                            } else {
                                                selectedApps - appInfo.packageName
                                            }
                                    },
                                )
                            }
                            item { Spacer(modifier = Modifier.height(16.dp)) }
                        }

                        item {
                            Text(
                                "ALL APPS",
                                color = Color.Gray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.5.sp,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }

                        items(unselectedList, key = { it.packageName }) { appInfo ->
                            AppRow(
                                appInfo = appInfo,
                                packageManager = packageManager,
                                isSelected = false,
                                themeColor = themeColor,
                                onToggle = { checked ->
                                    selectedApps =
                                        if (checked) {
                                            selectedApps + appInfo.packageName
                                        } else {
                                            selectedApps - appInfo.packageName
                                        }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppRow(
    appInfo: AppInfoData,
    packageManager: PackageManager,
    isSelected: Boolean,
    themeColor: Color,
    onToggle: (Boolean) -> Unit,
) {
    var appIcon by remember(appInfo.packageName) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(appInfo.packageName) {
        withContext(Dispatchers.IO) {
            try {
                val drawable = packageManager.getApplicationIcon(appInfo.applicationInfo)
                val bitmap = drawable.toBitmap(width = 120, height = 120).asImageBitmap()
                appIcon = bitmap
            } catch (e: Exception) {
            }
        }
    }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onToggle(!isSelected) }
                .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle(it) },
            colors =
                CheckboxDefaults.colors(
                    checkedColor = themeColor,
                    uncheckedColor = Color.Gray.copy(alpha = 0.5f),
                ),
        )
        Spacer(modifier = Modifier.width(12.dp))

        val icon = appIcon
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.05f)),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(appInfo.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(appInfo.packageName, color = Color.Gray, fontSize = 11.sp, maxLines = 1)
        }
        if (appInfo.isSystem) {
            Surface(
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    "SYS",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.Gray,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }
}

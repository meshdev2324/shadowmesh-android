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
    var showSystemApps by remember { mutableStateOf(value = false) }

    var selectedApps by remember { mutableStateOf(currentConfig.appList.toSet()) }
    var isEnabled by remember { mutableStateOf(currentConfig.enabled) }
    var mode by remember { mutableStateOf(currentConfig.mode) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val apps =
                try {
                    packageManager
                        .getInstalledApplications(0)
                        .asSequence()
                        .map { app ->
                            AppInfoData(
                                packageName = app.packageName,
                                label = packageManager.getApplicationLabel(app).toString(),
                                isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                                applicationInfo = app,
                            )
                        }.sortedBy { it.label.lowercase() }
                        .toList()
                } catch (_: Exception) {
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
            val filtered =
                allInstalledApps.filter { app ->
                    val matchesSearch =
                        app.label.contains(searchQuery, ignoreCase = true) ||
                            app.packageName.contains(searchQuery, ignoreCase = true)
                    matchesSearch && (showSystemApps || !app.isSystem)
                }
            filtered.partition { selectedApps.contains(it.packageName) }
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

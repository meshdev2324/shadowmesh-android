package com.shadowmesh.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadowmesh.app.VPNUiState
import com.shadowmesh.core_vpn.Config

/**
 * Screen for configuring custom DNS servers.
 * SOP 13: Clean architecture, technical gap fulfillment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomDNSSettingsScreen(
    uiState: VPNUiState,
    onAddDNS: (String) -> Unit,
    onRemoveDNS: (String) -> Unit,
    onBack: () -> Unit,
) {
    val themeColor = Color(uiState.themeColor)
    val customDNSServers = uiState.customDNSServers
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Custom DNS Servers", color = Color.White) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = Color.Transparent,
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        paddingValues,
                    ).padding(horizontal = 20.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var textState by remember { mutableStateOf("") }
                // DNS input: discoverable placeholder + numeric keyboard for the
                // IPv4-heavy use case. Placeholder doubles as the field's label.
                OutlinedTextField(
                    value = textState,
                    onValueChange = { textState = it },
                    placeholder = { Text("e.g. 1.1.1.1 or 2606:4700:4700::1111") },
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                        ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = Color.White),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = themeColor,
                            unfocusedPlaceholderColor = Color.Gray,
                            focusedPlaceholderColor = Color.Gray,
                        ),
                )
                Button(onClick = {
                    if (textState.isNotBlank()) {
                        onAddDNS(textState.trim())
                        textState = ""
                    }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = themeColor)) {
                    Text("Add DNS Server", color = Color.White)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Configured Servers (${customDNSServers.size})",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (customDNSServers.isEmpty()) {
                    Text(
                        "No custom DNS servers configured. Using defaults.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Config.DEFAULT_DNS_SERVERS.forEach { dns ->
                        DefaultDnsRow(dns)
                    }
                } else {
                    customDNSServers.forEach { dns ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Color.White.copy(alpha = 0.04f),
                                        RoundedCornerShape(12.dp),
                                    ).border(
                                        1.dp,
                                        themeColor.copy(alpha = 0.3f),
                                        RoundedCornerShape(12.dp),
                                    ).padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(dns, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                    Surface(color = themeColor, shape = CircleShape) {
                                        Text(
                                            "ACTIVE",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            color = Color.White,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Black,
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { onRemoveDNS(dns) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove DNS server",
                                    tint = Color(0xFFEF4444).copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DefaultDnsRow(dns: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    Color.White.copy(alpha = 0.02f),
                    RoundedCornerShape(12.dp),
                ).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(dns, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
            Surface(color = Color.Gray.copy(alpha = 0.3f), shape = CircleShape) {
                Text(
                    "ACTIVE (DEFAULT)",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }
}

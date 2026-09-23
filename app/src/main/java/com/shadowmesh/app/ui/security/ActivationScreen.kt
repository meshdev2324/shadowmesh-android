package com.shadowmesh.app.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadowmesh.app.R
import com.shadowmesh.app.VPNManagerViewModel
import com.shadowmesh.ui_kit.theme.ActivationCodeInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivationScreen(
    viewModel: VPNManagerViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val themeColor = Color(uiState.themeColor)
    val haptic = LocalHapticFeedback.current
    var activationCode by remember { mutableStateOf("") }
    var showQRScanner by remember { mutableStateOf(false) }

    if (showQRScanner) {
        com.shadowmesh.app.ui.pairing.QRScannerScreen(
            viewModel = viewModel,
            onDismiss = { showQRScanner = false },
            onResult = { result ->
                activationCode = result
                showQRScanner = false
            },
        )
        return
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color(0xFF0C0C14))
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.activation_title),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.activation_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF94A3B8),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))

        ActivationCodeInput(
            value = activationCode,
            onValueChange = { activationCode = it },
            themeColor = themeColor,
            onScanClick = { showQRScanner = true },
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { 
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.activate(activationCode) 
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = themeColor,
                ),
            enabled = activationCode.length == 25 && !uiState.isActivating,
        ) {
            if (uiState.isActivating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.activation_button), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (uiState.errorMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                uiState.errorMessage ?: "Unknown Error",
                color = Color(0xFFEF4444),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

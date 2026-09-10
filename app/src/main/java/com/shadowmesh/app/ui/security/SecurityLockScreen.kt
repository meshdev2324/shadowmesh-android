package com.shadowmesh.app.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.shadowmesh.app.VPNManagerViewModel

@Composable
fun SecurityLockScreen(
    viewModel: VPNManagerViewModel,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showPinPad by remember { mutableStateOf(false) }
    var lockSubtitle by remember { mutableStateOf("Enter standard or duress PIN") }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (context is FragmentActivity) {
            viewModel.authenticateWithBiometrics(
                activity = context,
                onSuccess = { onUnlocked() },
                onFailure = { showPinPad = true },
            )
        } else {
            showPinPad = true
        }
    }

    if (showPinPad) {
        com.shadowmesh.app.ui.settings.PINPadScreen(
            title = if (isError) "Invalid PIN" else "App Locked",
            subtitle = lockSubtitle,
            onPinComplete = { pin ->
                if (viewModel.verifyPin(pin)) {
                    onUnlocked()
                } else {
                    isError = true
                    lockSubtitle = "Incorrect PIN. Try again."
                }
            },
            onCancel = {
                // Exit app if they can't/won't unlock
                if (context is FragmentActivity) {
                    context.moveTaskToBack(true)
                }
            },
        )
    } else {
        // ... (loading view remains same)
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(Color(0xFF0C0C14)),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Locked",
                tint = Color(0xFF10B981),
                modifier = Modifier.size(64.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Authenticating...",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
        }
    }
}

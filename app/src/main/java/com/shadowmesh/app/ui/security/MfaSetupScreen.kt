package com.shadowmesh.app.ui.security

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadowmesh.app.VPNManagerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MfaSetupScreen(
    viewModel: VPNManagerViewModel,
    onDismiss: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val themeColor = Color(uiState.themeColor)
    var verificationCode by remember { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current

    // Apple-grade press physics on the primary action, matching the
    // ActivationScreen control family (0.97 scale, bouncy return).
    val buttonInteraction = remember { MutableInteractionSource() }
    val pressed by buttonInteraction.collectIsPressedAsState()
    val buttonScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessHigh,
            ),
        label = "buttonScale",
    )

    val qrBitmap =
        remember(uiState.mfaQrCode) {
            uiState.mfaQrCode?.let { base64 ->
                val cleanBase64 = base64.substringAfter("base64,")
                val decodedString = Base64.decode(cleanBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
            }
        }

    LaunchedEffect(Unit) {
        if (uiState.mfaQrCode == null) {
            viewModel.startMfaSetup()
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Text(
            "Secure Your Session",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Add an extra layer of security to your ShadowMesh account using TOTP (Google Authenticator, Authy, etc).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.weight(1f))

        if (qrBitmap != null) {
            Box(
                modifier =
                    Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "MFA QR Code",
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Secret: ${uiState.mfaSecret}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        } else {
            Box(modifier = Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = themeColor)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        OutlinedTextField(
            value = verificationCode,
            onValueChange = { if (it.length <= 6) verificationCode = it },
            label = {
                Text(
                    "6-Digit Verification Code",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle =
                LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = themeColor,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.completeMfaSetup(verificationCode)
            },
            interactionSource = buttonInteraction,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .graphicsLayer {
                        scaleX = buttonScale
                        scaleY = buttonScale
                    },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = themeColor),
            enabled = verificationCode.length == 6,
        ) {
            Text("Verify and Enable MFA", fontWeight = FontWeight.Bold)
        }

        TextButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Setup Later", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (uiState.errorMessage != null) {
            Text(
                uiState.errorMessage ?: "Verification Failed",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier =
                    Modifier
                        .padding(top = 16.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                textAlign = TextAlign.Center,
            )
        }
    }
}

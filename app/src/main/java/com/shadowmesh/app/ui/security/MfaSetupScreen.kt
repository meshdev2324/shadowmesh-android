package com.shadowmesh.app.ui.security

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
                .background(Color(0xFF0C0C14))
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Text(
            "Secure Your Session",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Add an extra layer of security to your ShadowMesh account using TOTP (Google Authenticator, Authy, etc).",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF94A3B8),
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
                color = Color.Gray,
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
            label = { Text("6-Digit Verification Code", color = Color(0xFF64748B)) },
            modifier = Modifier.fillMaxWidth(),
            textStyle =
                LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = themeColor,
                    unfocusedBorderColor = Color(0xFF1E293B),
                ),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { viewModel.completeMfaSetup(verificationCode) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = themeColor),
            enabled = verificationCode.length == 6,
        ) {
            Text("Verify and Enable MFA", fontWeight = FontWeight.Bold)
        }

        TextButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Setup Later", color = Color.Gray)
        }

        if (uiState.errorMessage != null) {
            Text(
                uiState.errorMessage ?: "Verification Failed",
                color = Color(0xFFEF4444),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 16.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

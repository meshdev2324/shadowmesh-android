package com.shadowmesh.ui_kit.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ActivationCodeInput(value: String, onValueChange: (String) -> Unit, themeColor: Color, onScanClick: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val chunks = value.padEnd(25, ' ').chunked(5)
    val interactionSource = remember { MutableInteractionSource() }
    
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "ACCESS KEY",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    color = Color.White.copy(alpha = 0.4f)
                )
            )
            
            Surface(
                onClick = onScanClick,
                color = themeColor.copy(alpha = 0.1f),
                shape = CircleShape,
                border = BorderStroke(1.dp, themeColor.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, null, tint = themeColor, modifier = Modifier.size(14.dp))
                    Text("SCAN", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black, color = themeColor))
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) { focusRequester.requestFocus() },
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            chunks.forEachIndexed { index, chunk ->
                val segmentStart = index * 5
                val isFilled = value.length >= segmentStart + 5
                val isActive = value.length in segmentStart until (segmentStart + 5)
                
                val borderColor by animateColorAsState(
                    targetValue = when {
                        isFilled -> themeColor
                        isActive -> themeColor.copy(alpha = 0.6f)
                        else -> Color.White.copy(alpha = 0.08f)
                    },
                    label = "border"
                )
                
                val bgColor by animateColorAsState(
                    targetValue = if (isActive) themeColor.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.3f),
                    label = "bg"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(bgColor)
                        .border(
                            width = if (isActive) 1.5.dp else 1.dp,
                            color = borderColor,
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (chunk.all { it == ' ' }) {
                        Text(
                            "•••••",
                            color = Color.White.copy(alpha = 0.1f),
                            fontSize = 12.sp,
                            letterSpacing = 1.sp
                        )
                    } else {
                        Text(
                            text = chunk.trim(),
                            color = if (isFilled) themeColor else Color.White,
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                textAlign = TextAlign.Center
                            )
                        )
                    }
                }
            }
        }

        BasicTextField(
            value = value,
            onValueChange = { input ->
                val filtered = input.uppercase().filter { it.isLetterOrDigit() }.take(25)
                onValueChange(filtered)
            },
            modifier = Modifier.size(0.dp).alpha(0f).focusRequester(focusRequester),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                autoCorrectEnabled = false
            )
        )
        
        val progress by animateFloatAsState(targetValue = value.length / 25f, label = "progress")
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(2.dp).clip(CircleShape),
            color = themeColor,
            trackColor = Color.White.copy(alpha = 0.05f)
        )
    }
}

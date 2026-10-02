package com.shadowmesh.app.ui.alert

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shadowmesh.app.alert.Alert
import com.shadowmesh.app.alert.AlertSeverity

/**
 * The one alert renderer (RFC-028). Every screen used to draw its own error
 * text with its own severity and lifetime; now this composable renders the
 * highest-severity live alert, identically, wherever it is mounted.
 *
 * Mounted once in `MainActivity` above the screen switch, so an alert raised
 * on any screen is visible there — including on sovereignty-sensitive screens
 * (FLAG_SECURE gates the whole window, and alerts add no new bypass).
 *
 * Accessibility: the row is a polite live region, so a screen reader announces
 * the alert when it appears; the icon is decorative and excluded from the
 * semantics tree.
 */
@Composable
fun AlertHost(
    alerts: List<Alert>,
    onDismiss: (Alert) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Highest severity wins; newest wins ties. The repository stores newest
    // first, so maxBy on the ordered list keeps determinism without re-sorting
    // the whole channel here.
    val current = remember(alerts) {
        alerts.maxByOrNull { it.severity.ordinal }
    }

    AnimatedVisibility(
        visible = current != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        if (current != null) {
            AlertRow(alert = current, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun AlertRow(alert: Alert, onDismiss: (Alert) -> Unit) {
    val (accent, icon, label) = when (alert.severity) {
        AlertSeverity.CRITICAL -> Triple(Color(0xFFEF4444), Icons.Filled.Error, "Critical alert")
        AlertSeverity.WARNING -> Triple(Color(0xFFF59E0B), Icons.Filled.Warning, "Warning")
        AlertSeverity.INFO -> Triple(Color(0xFF22D3EE), Icons.Filled.Info, "Notice")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(
                color = Color(0xFF0A0A0F).copy(alpha = 0.94f),
                shape = RoundedCornerShape(12.dp),
            )
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "$label: ${alert.message}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier
                .padding(start = 12.dp)
                .size(20.dp)
                .clearAndSetSemantics { },
        )
        Text(
            text = alert.message,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (alert.severity.isBlocking) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        )
        if (alert.sticky) {
            IconButton(onClick = { onDismiss(alert) }) {
                Text(
                    text = "Dismiss",
                    color = accent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

package com.itantra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.core.model.DeliveryStatus
import com.itantra.core.model.Language
import com.itantra.ui.theme.ForestSage
import com.itantra.ui.theme.StatusError
import com.itantra.ui.theme.StatusWarning

/**
 * Prominent banner indicating Demo Transport Mode.
 * Ensures simulated transmission is never confused with real LoRa RF transmission.
 */
@Composable
fun DemoModeBanner(modifier: Modifier = Modifier) {
    val transport by com.itantra.ITantraApplication.instance.activeTransport.collectAsState()
    if (!transport.isDemoMode) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFFFFBE6))
            .border(1.dp, Color(0xFFFFE58F), RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Demo Mode",
                tint = Color(0xFFD48806),
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = "DEMO TRANSPORT MODE ACTIVE",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF874D00)
                )
                Text(
                    text = "Simulated local loopback. Physical ESP32 and SX1278 LoRa hardware not attached.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFA76500)
                )
            }
        }
    }
}

@Composable
fun DeliveryStatusBadge(
    status: DeliveryStatus,
    modifier: Modifier = Modifier
) {
    val (icon, color) = when (status) {
        DeliveryStatus.QUEUED -> Pair(Icons.Default.Schedule, Color.Gray)
        DeliveryStatus.SENDING -> Pair(Icons.Default.HourglassTop, StatusWarning)
        DeliveryStatus.SENT_TO_NODE -> Pair(Icons.Default.Sensors, MaterialTheme.colorScheme.primary)
        DeliveryStatus.RADIO_ACKNOWLEDGED -> Pair(Icons.Default.Check, ForestSage)
        DeliveryStatus.DELIVERED -> Pair(Icons.Default.DoneAll, ForestSage)
        DeliveryStatus.FAILED -> Pair(Icons.Default.ErrorOutline, StatusError)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = status.label,
            tint = color,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
fun LanguagePill(
    language: Language,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "${language.displayName} (${language.nativeLabel})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Accessible button with enforced >= 48dp tap target (R-03).
 */
@Composable
fun AccessibleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: String
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minWidth = 88.dp, minHeight = 48.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp))
    }
}

@Composable
fun EmptyStateView(
    title: String,
    description: String,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (actionLabel != null && onActionClick != null) {
            Spacer(modifier = Modifier.height(16.dp))
            AccessibleButton(onClick = onActionClick, text = actionLabel)
        }
    }
}

@Composable
fun LoadingStateView(
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

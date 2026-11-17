package com.itantra.feature.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itantra.core.model.AlertType
import com.itantra.ui.components.DeliveryStatusBadge
import com.itantra.ui.components.DemoModeBanner
import com.itantra.ui.theme.EarthTerracotta
import com.itantra.ui.theme.ForestSage
import com.itantra.ui.theme.StatusError

@Composable
fun EmergencyAlertScreen(
    viewModel: AlertViewModel = viewModel()
) {
    val dispatchState by viewModel.dispatchState.collectAsState()
    val customText by viewModel.customAlertText.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DemoModeBanner()

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Emergency Alert Dispatch",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "High-priority radio frames pre-empt ordinary queues",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Legal & Audio Policy Notice Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFF5F5))
                    .border(1.dp, Color(0xFFFEB2B2), RoundedCornerShape(8.dp))
                    .padding(14.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Policy Notice",
                        tint = StatusError,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Audio Policy & Delivery Disclaimer",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = StatusError
                        )
                        Text(
                            text = "Android audio policies may prevent overriding silent mode or Do Not Disturb without explicit special system permissions. Fallback visual banner and haptic vibration are triggered. Alerts are not guaranteed emergency services; wait for radio peer acknowledgement.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4A5568)
                        )
                    }
                }
            }

            // Active Alert Dispatch Status
            dispatchState.lastAlertSent?.let { alertType ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Active Alert: ${alertType.title}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = EarthTerracotta
                            )
                            if (dispatchState.isTransmitting) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        }

                        dispatchState.deliveryStatus?.let { status ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "Link State:", style = MaterialTheme.typography.bodyMedium)
                                DeliveryStatusBadge(status = status)
                            }
                        }

                        if (dispatchState.acknowledgedByPeer) {
                            Text(
                                text = "Peer Node B confirmed reception.",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = ForestSage
                            )
                        }
                    }
                }
            }

            // Emergency Presets Grid
            Text(
                text = "Preset Tactical Alerts",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            AlertPresetButton(
                title = AlertType.SOS.title,
                description = AlertType.SOS.defaultText,
                icon = Icons.Default.Emergency,
                color = EarthTerracotta,
                onClick = { viewModel.sendEmergencyAlert(AlertType.SOS) }
            )

            AlertPresetButton(
                title = AlertType.MEDICAL.title,
                description = AlertType.MEDICAL.defaultText,
                icon = Icons.Default.LocalHospital,
                color = EarthTerracotta,
                onClick = { viewModel.sendEmergencyAlert(AlertType.MEDICAL) }
            )

            AlertPresetButton(
                title = AlertType.EVACUATION.title,
                description = AlertType.EVACUATION.defaultText,
                icon = Icons.Default.Warning,
                color = StatusError,
                onClick = { viewModel.sendEmergencyAlert(AlertType.EVACUATION) }
            )

            AlertPresetButton(
                title = AlertType.SAFE.title,
                description = AlertType.SAFE.defaultText,
                icon = Icons.Default.VerifiedUser,
                color = ForestSage,
                onClick = { viewModel.sendEmergencyAlert(AlertType.SAFE) }
            )

            // Custom Emergency Message Input
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Custom Emergency Message",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { viewModel.updateCustomText(it) },
                        placeholder = { Text(text = "Enter emergency coordinates or message...") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        shape = RoundedCornerShape(6.dp)
                    )
                    Button(
                        onClick = { viewModel.sendEmergencyAlert(AlertType.CUSTOM) },
                        enabled = customText.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EarthTerracotta,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(imageVector = Icons.Default.Send, contentDescription = "Send Custom Alert")
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(text = "Broadcast Custom Alert", style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertPresetButton(
    title: String,
    description: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.12f),
            contentColor = color
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(imageVector = icon, contentDescription = title, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = color
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

package com.itantra.feature.onboarding

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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.ui.components.DemoModeBanner

@Composable
fun OnboardingScreen(
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DemoModeBanner()

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "iTantra User Guide",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "SIH26173: Offline Speech-over-Text over BLE and 433 MHz LoRa",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            GuideSection(
                icon = Icons.Default.Radio,
                title = "Hardware Architecture",
                description = "Each phone pairs over BLE with an ESP32 bridge node. The nodes communicate over 433 MHz LoRa. Speech models never run on the ESP32. Speech is converted to compact text on your phone, sent over LoRa, and reconstructed as audio on the receiver phone."
            )

            GuideSection(
                icon = Icons.Default.Lock,
                title = "Offline Privacy and Security",
                description = "All STT and TTS models execute 100% locally on your phone. No cloud connection is ever used. Audio recordings are held in temporary memory and wiped immediately after transcription. Every message is encrypted using AES-256-GCM with Additional Authenticated Data (AAD)."
            )

            GuideSection(
                icon = Icons.Default.Bluetooth,
                title = "Permissions Rationale",
                description = "Nearby Devices permission is required to scan and communicate with your ESP32 node via Bluetooth Low Energy (BLE). Microphone permission is required to capture your voice locally for on-device transcription."
            )

            GuideSection(
                icon = Icons.Default.Download,
                title = "Model Packs Setup",
                description = "Before going into field operations without internet, language model packs must be installed once on this device. See the Models tab to verify checksums and status for the 10 supported Indic languages."
            )

            GuideSection(
                icon = Icons.Default.CheckCircle,
                title = "Pairing Procedure",
                description = "To pair with your peer, scan their QR code or verify the 6-digit confirmation code displayed on both screens. Directional encryption keys are derived independently."
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = "Acknowledge and Continue",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GuideSection(
    icon: ImageVector,
    title: String,
    description: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

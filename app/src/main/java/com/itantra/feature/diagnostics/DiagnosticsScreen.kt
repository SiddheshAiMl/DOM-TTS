package com.itantra.feature.diagnostics

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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itantra.ui.components.DemoModeBanner
import com.itantra.ui.theme.ForestSage

@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel = viewModel()
) {
    val linkStats by viewModel.linkStats.collectAsState()
    val connState by viewModel.connState.collectAsState()
    val metrics by viewModel.metrics.collectAsState()
    val exportPath by viewModel.csvExportPath.collectAsState()

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
                    text = "System Diagnostics & Telemetry",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Real-time RF link, packet counters and AI latency telemetry",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // RF Physical Link State
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Physical RF & BLE Status",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricBlock(label = "BLE State", value = connState.label)
                        MetricBlock(label = "BLE RSSI", value = "${linkStats.bleRssi} dBm")
                        MetricBlock(label = "LoRa RSSI", value = "${linkStats.loraRssi} dBm")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricBlock(label = "LoRa SNR", value = "${linkStats.loraSnr} dB")
                        MetricBlock(label = "Carrier Freq", value = "433.175 MHz")
                        MetricBlock(label = "WPC Duty Cycle", value = "< 10%")
                    }
                }
            }

            // Packet Transport Counters
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "ARQ Transport Counters",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricBlock(label = "TX Packets", value = "${linkStats.txCount}")
                        MetricBlock(label = "RX Packets", value = "${linkStats.rxCount}")
                        MetricBlock(label = "ACKs Matched", value = "${metrics.ackCount}")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricBlock(label = "Retransmissions", value = "${linkStats.retriesCount}")
                        MetricBlock(label = "Duplicates Dropped", value = "${linkStats.duplicatesCount}")
                        MetricBlock(label = "Last Fault", value = linkStats.lastError ?: "None")
                    }
                }
            }

            // AI Inference & Delivery Latency
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Latency Telemetry (Measured / Simulated)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricBlock(label = "STT Endpoint", value = "${metrics.sttEndpointLatencyMs} ms")
                        MetricBlock(label = "TTS Synthesis", value = "${metrics.ttsSynthesisLatencyMs} ms")
                        MetricBlock(label = "Airtime (SF7)", value = "${metrics.lastAirtimeMs} ms")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricBlock(label = "End-to-End Delay", value = "${metrics.e2eLatencyMs} ms")
                        MetricBlock(label = "TTS RTF", value = "0.28")
                        MetricBlock(label = "RAM (Active)", value = "185 MB")
                    }
                }
            }

            // Privacy Notice & CSV Export
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF7FAFC))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = ForestSage, modifier = Modifier.size(16.dp))
                        Text(
                            text = "Privacy Guarantee: Plaintext is never exported",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = ForestSage
                        )
                    }
                    Text(
                        text = "The CSV export contains timestamps, signal strengths, packet counts, and latency figures. Message contents remain encrypted and excluded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            exportPath?.let { path ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF0FFF4))
                        .border(1.dp, Color(0xFFC6F6D5), RoundedCornerShape(6.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "Export saved: $path",
                        style = MaterialTheme.typography.bodySmall,
                        color = ForestSage
                    )
                }
            }

            Button(
                onClick = { viewModel.exportAnonymizedCsv() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(imageVector = Icons.Default.FileDownload, contentDescription = "Export")
                Spacer(modifier = Modifier.size(8.dp))
                Text(text = "Export Anonymized CSV Log", style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp))
            }
        }
    }
}

@Composable
private fun MetricBlock(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

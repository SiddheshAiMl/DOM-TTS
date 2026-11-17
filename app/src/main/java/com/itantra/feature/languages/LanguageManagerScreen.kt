package com.itantra.feature.languages

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.itantra.core.model.Language
import com.itantra.speech.manager.LanguagePackInfo
import com.itantra.speech.manager.ModelStatus
import com.itantra.ui.components.DemoModeBanner
import com.itantra.ui.theme.ForestSage
import com.itantra.ui.theme.StatusWarning

@Composable
fun LanguageManagerScreen(
    viewModel: LanguageViewModel = viewModel()
) {
    val languagePacks by viewModel.languagePacks.collectAsState()
    val prefs by viewModel.userPreferences.collectAsState()
    val notice by viewModel.statusNotice.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DemoModeBanner()

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Language & Model Manager",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "10 Target Languages: 100% On-Device AI Models",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Notice / Feedback snack
            notice?.let { msg ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFEBF8FF))
                        .border(1.dp, Color(0xFFBEE3F8), RoundedCornerShape(6.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2B6CB0)
                    )
                }
            }

            // Active Language Configuration
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Current Language Configuration",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Input STT: ${prefs.inputLanguage.displayName} (${prefs.inputLanguage.nativeLabel})",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Output Voice: ${prefs.outputLanguage.displayName}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // 10 Language Matrix
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(languagePacks, key = { it.language.code }) { pack ->
                    LanguagePackItem(
                        pack = pack,
                        isInput = prefs.inputLanguage == pack.language,
                        isOutput = prefs.outputLanguage == pack.language,
                        onSetInput = { viewModel.setInputLanguage(pack.language) },
                        onSetOutput = { viewModel.setOutputLanguage(pack.language) },
                        onVerify = { viewModel.verifyChecksum(pack) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguagePackItem(
    pack: LanguagePackInfo,
    isInput: Boolean,
    isOutput: Boolean,
    onSetInput: () -> Unit,
    onSetOutput: () -> Unit,
    onVerify: () -> Unit
) {
    val statusColor = when (pack.status) {
        ModelStatus.READY -> ForestSage
        ModelStatus.NOT_INSTALLED -> MaterialTheme.colorScheme.onSurfaceVariant
        ModelStatus.VERIFYING -> StatusWarning
        ModelStatus.CORRUPTED -> MaterialTheme.colorScheme.error
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${pack.language.displayName} (${pack.language.nativeLabel})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Script: ${pack.language.script} | ISO: ${pack.language.code}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box(
                    modifier = Modifier
                        .background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = pack.status.label,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = statusColor
                    )
                }
            }

            Text(
                text = "License: ${pack.license} | Provenance: ${pack.provenance}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "Package: ${pack.sttModelName} + ${pack.ttsModelName} (~${pack.estimatedSizeBytes / 1_000_000} MB)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Selection Controls (>= 48dp height)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSetInput,
                    enabled = !isInput,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    if (isInput) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(text = if (isInput) "Active Input" else "Set Input", style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = onSetOutput,
                    enabled = !isOutput,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    if (isOutput) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(text = if (isOutput) "Active Voice" else "Set Voice", style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = onVerify,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Text(text = "Verify", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

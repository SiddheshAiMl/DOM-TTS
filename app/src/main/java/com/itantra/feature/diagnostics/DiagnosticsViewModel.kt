package com.itantra.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itantra.ITantraApplication
import com.itantra.core.model.DiagnosticMetrics
import com.itantra.core.transport.LinkStatistics
import com.itantra.core.transport.TransportConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsViewModel : ViewModel() {
    private val app = ITantraApplication.instance

    val linkStats: StateFlow<LinkStatistics> = app.activeTransport
        .flatMapLatest { it.linkStatistics }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LinkStatistics())

    val connState: StateFlow<TransportConnectionState> = app.activeTransport
        .flatMapLatest { it.connectionState }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TransportConnectionState.DISCONNECTED)

    val metrics: StateFlow<DiagnosticMetrics> = linkStats
        .map { stats ->
            DiagnosticMetrics(
                bleRssi = stats.bleRssi,
                loraRssi = stats.loraRssi,
                loraSnr = stats.loraSnr,
                txCount = stats.txCount,
                rxCount = stats.rxCount,
                ackCount = stats.txCount,
                retryCount = stats.retriesCount,
                duplicateCount = stats.duplicatesCount,
                lastError = stats.lastError
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DiagnosticMetrics())

    private val _csvExportPath = MutableStateFlow<String?>(null)
    val csvExportPath: StateFlow<String?> = _csvExportPath.asStateFlow()

    fun exportAnonymizedCsv() {
        viewModelScope.launch {
            val dir = app.getExternalFilesDir(null) ?: app.filesDir
            val logsDir = File(dir, "logs")
            logsDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(logsDir, "itantra_diagnostics_$timestamp.csv")

            val currentMetrics = metrics.value
            val currentStats = linkStats.value

            val csvContent = buildString {
                appendLine("timestamp_utc,event,ble_rssi,lora_rssi,lora_snr,tx_packets,rx_packets,retries,duplicates,stt_latency_ms,tts_latency_ms,e2e_latency_ms")
                appendLine("${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())},SAMPLE,${currentStats.bleRssi},${currentStats.loraRssi},${currentStats.loraSnr},${currentStats.txCount},${currentStats.rxCount},${currentStats.retriesCount},${currentStats.duplicatesCount},${currentMetrics.sttEndpointLatencyMs},${currentMetrics.ttsSynthesisLatencyMs},${currentMetrics.e2eLatencyMs}")
            }

            file.writeText(csvContent)
            _csvExportPath.value = file.absolutePath
        }
    }
}

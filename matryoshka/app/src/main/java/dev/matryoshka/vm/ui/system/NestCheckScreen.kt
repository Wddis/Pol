package dev.matryoshka.vm.ui.system

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.matryoshka.vm.R
import dev.matryoshka.vm.core.debug.TesterLog
import dev.matryoshka.vm.core.nest.NestProbe
import dev.matryoshka.vm.core.nest.ProbeLevel
import dev.matryoshka.vm.core.nest.ProbeReport
import dev.matryoshka.vm.ui.BackButton
import dev.matryoshka.vm.ui.components.ListRow
import dev.matryoshka.vm.ui.components.Panel
import dev.matryoshka.vm.ui.components.SectionTitle
import dev.matryoshka.vm.ui.manager
import dev.matryoshka.vm.ui.theme.Brand
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NestCheckScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val m = manager()
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf(TesterLog.lastProbe) }
    var running by remember { mutableStateOf(false) }

    fun runProbe() {
        running = true
        scope.launch {
            report = NestProbe(context).run().also { TesterLog.lastProbe = it }
            running = false
        }
    }
    LaunchedEffect(Unit) { if (report == null) runProbe() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nest_check)) },
                navigationIcon = { BackButton(onBack) },
                actions = { IconButton(onClick = { runProbe() }, enabled = !running) { Icon(Icons.Outlined.Refresh, null) } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp)) {
            item {
                SummaryCard(report, running)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { openBatterySettings(context) }) {
                        Icon(Icons.Outlined.BatteryChargingFull, null); Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.allow_background))
                    }
                    OutlinedButton(onClick = { openAutostart(context) }) { Text(stringResource(R.string.autostart)) }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { scope.launch { TesterLog.share(context, TesterLog.collect(context, m)) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.BugReport, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.send_log))
                }
            }
            val r = report
            if (r != null) {
                val groups = listOf(
                    R.string.probe_engine to r.items.filter { it.key.startsWith("check.") },
                    R.string.probe_phone to r.items.filter { !it.key.startsWith("check.") && it.level != ProbeLevel.INFO },
                    R.string.probe_info to r.items.filter { !it.key.startsWith("check.") && it.level == ProbeLevel.INFO },
                )
                for ((title, list) in groups) {
                    if (list.isEmpty()) continue
                    item { SectionTitle(stringResource(title)) }
                    items(list, key = { it.key }) { p ->
                        ListRow(
                            icon = when (p.level) {
                                ProbeLevel.OK -> Icons.Outlined.CheckCircle
                                ProbeLevel.WARN -> Icons.Outlined.WarningAmber
                                ProbeLevel.FAIL -> Icons.Outlined.ErrorOutline
                                ProbeLevel.INFO -> Icons.Outlined.Info
                            },
                            title = p.title,
                            subtitle = p.detail,
                            tint = when (p.level) {
                                ProbeLevel.OK -> Brand.Ok
                                ProbeLevel.WARN -> Brand.Amber
                                ProbeLevel.FAIL -> Brand.Error
                                ProbeLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(report: ProbeReport?, running: Boolean) {
    Panel {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.RocketLaunch, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        running || report == null -> stringResource(R.string.probe_running)
                        report.failures == 0 -> stringResource(R.string.probe_ready)
                        else -> stringResource(R.string.probe_problems, report.failures)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.probe_explain), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (running) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@SuppressLint("BatteryLife")
private fun openBatterySettings(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(direct) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

/** MIUI keeps its own "Autostart" switch; other ROMs fall back to the app details page. */
private fun openAutostart(context: Context) {
    val miui = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
    runCatching { context.startActivity(miui) }.onFailure {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
}

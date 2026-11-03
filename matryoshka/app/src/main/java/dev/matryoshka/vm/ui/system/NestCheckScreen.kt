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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.collectAsState
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
import dev.matryoshka.vm.core.nest.NestDisplayView
import dev.matryoshka.vm.core.nest.NestGuest
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
                Spacer(Modifier.height(12.dp))
                NestBootPanel()
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

/** Tester tool: boots the real Android 11 guest under Nest and shows its boot log live. */
@Composable
private fun NestBootPanel() {
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val state by NestGuest.state.collectAsState()
    var lines by remember { mutableStateOf(emptyList<String>()) }
    var stage by remember { mutableStateOf<String?>(null) }
    var showLogcat by remember { mutableStateOf(false) }
    var showScreen by remember { mutableStateOf(true) }
    var binderTrace by remember { mutableStateOf(NestGuest.verboseBinder) }
    var gpu by remember { mutableStateOf(NestGuest.useGpu) }
    LaunchedEffect(Unit) { NestGuest.refresh(context) }
    androidx.compose.runtime.DisposableEffect(state) {
        view.keepScreenOn = state is NestGuest.State.Running  // MIUI may freeze a backgrounded boot
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(state, showLogcat) {
        while (state is NestGuest.State.Running || state is NestGuest.State.Stopped) {
            val f = if (showLogcat) NestGuest.logcat(context) else NestGuest.kmsg(context)
            lines = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { NestGuest.tail(f, 30) }
            stage = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { NestGuest.bootStage(context) }
            if (state is NestGuest.State.Stopped) break
            kotlinx.coroutines.delay(if (showScreen) 6000 else 2000)  // the guest's screen needs the CPU more
        }
    }
    Panel {
        Column(Modifier.padding(18.dp)) {
            Text(stringResource(R.string.nest_boot_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.nest_boot_explain), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            val s = state
            val status = when (s) {
                NestGuest.State.NotInstalled -> stringResource(R.string.nest_boot_not_installed)
                is NestGuest.State.Downloading -> stringResource(R.string.nest_boot_downloading, s.done / 1_000_000, s.total / 1_000_000)
                NestGuest.State.Verifying -> stringResource(R.string.nest_boot_verifying)
                is NestGuest.State.Extracting -> stringResource(R.string.nest_boot_extracting, (100 * s.done / s.total.coerceAtLeast(1)).toInt())
                NestGuest.State.Ready -> stringResource(R.string.nest_boot_ready)
                is NestGuest.State.Running -> stringResource(R.string.nest_boot_running, stage ?: "…")
                is NestGuest.State.Stopped -> stringResource(R.string.nest_boot_stopped, s.exitCode, s.seconds, stage ?: "—")
                is NestGuest.State.Failed -> stringResource(R.string.nest_boot_failed, s.message)
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            when (s) {
                is NestGuest.State.Downloading -> LinearProgressIndicator(
                    progress = { s.done.toFloat() / s.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                is NestGuest.State.Extracting -> LinearProgressIndicator(
                    progress = { s.done.toFloat() / s.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                NestGuest.State.Verifying, is NestGuest.State.Running ->
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                else -> Unit
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (s) {
                    NestGuest.State.NotInstalled, is NestGuest.State.Failed ->
                        Button(onClick = { NestGuest.install(context) }) {
                            Text(stringResource(if (NestGuest.installedImage(context) == null) R.string.nest_boot_download else R.string.nest_boot_retry))
                        }
                    NestGuest.State.Ready, is NestGuest.State.Stopped ->
                        Button(onClick = { NestGuest.verboseBinder = binderTrace; NestGuest.boot(context) }) {
                            Text(stringResource(R.string.nest_boot_start))
                        }
                    is NestGuest.State.Running ->
                        Button(onClick = { NestGuest.stop() }) { Text(stringResource(R.string.nest_boot_stop)) }
                    else -> Unit
                }
                if (s is NestGuest.State.Running || s is NestGuest.State.Stopped) {
                    OutlinedButton(onClick = { showLogcat = !showLogcat }) {
                        Text(stringResource(if (showLogcat) R.string.nest_boot_show_kmsg else R.string.nest_boot_show_logcat))
                    }
                }
            }
            if (s is NestGuest.State.Ready || s is NestGuest.State.Stopped) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = binderTrace, onCheckedChange = { binderTrace = it })
                    Text(stringResource(R.string.nest_boot_binder_trace), style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = gpu, onCheckedChange = { gpu = it; NestGuest.useGpu = it })
                    Text(stringResource(R.string.nest_boot_gpu), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (s is NestGuest.State.Running) {
                OutlinedButton(onClick = { showScreen = !showScreen }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(if (showScreen) R.string.nest_boot_hide_screen else R.string.nest_boot_show_screen))
                }
                if (showScreen) {
                    val waiting = stringResource(R.string.nest_boot_screen_waiting)
                    // The guest's display (frames from its composer; touches go back to it).
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { NestDisplayView(it, NestGuest.stateDir(it.applicationContext), waiting) },
                        modifier = Modifier.padding(top = 8.dp).height(520.dp)
                            .aspectRatio(NestGuest.displayAspect, matchHeightConstraintsFirst = true),
                    )
                }
            }
            if (lines.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        lines.joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

package dev.matryoshka.vm.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.matryoshka.vm.R
import dev.matryoshka.vm.core.host.HostReport
import dev.matryoshka.vm.core.host.Support
import dev.matryoshka.vm.ui.components.ListRow
import dev.matryoshka.vm.ui.components.Meter
import dev.matryoshka.vm.ui.components.Panel
import dev.matryoshka.vm.ui.components.SectionTitle
import dev.matryoshka.vm.ui.engineName
import dev.matryoshka.vm.ui.manager
import dev.matryoshka.vm.ui.theme.Brand
import kotlinx.coroutines.launch

@Composable
fun SystemScreen(onAbout: () -> Unit, onNestCheck: () -> Unit = {}) {
    val m = manager()
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<HostReport?>(null) }
    var askedRoot by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { report = m.hostReport(askRoot = false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(top = 8.dp, start = 4.dp)) {
                Text(stringResource(R.string.tab_system), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.system_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Panel {
                ListRow(
                    Icons.Outlined.RocketLaunch, stringResource(R.string.nest_check), stringResource(R.string.nest_check_desc),
                    onClick = onNestCheck,
                )
            }
        }
        val r = report
        if (r == null) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 24.dp)) }
        } else {
            item {
                SectionTitle(r.device)
                Panel {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${r.androidVersion} · ${r.abi}", style = MaterialTheme.typography.bodyMedium)
                        Text("Linux ${r.kernel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Meter(stringResource(R.string.memory), "%.1f GB".format(r.totalRamMb / 1024f), (r.totalRamMb / 16384f), Brand.Violet)
                        Meter(stringResource(R.string.free_storage), "%.1f GB".format(r.freeStorageGb), (r.freeStorageGb / 256).toFloat(), Brand.Teal)
                        Meter(stringResource(R.string.cpu_cores), r.cpuCores.toString(), r.cpuCores / 12f, Brand.Coral)
                    }
                }
                if (!askedRoot) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        askedRoot = true
                        scope.launch { report = null; report = m.hostReport(askRoot = true) }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.deep_check)) }
                }
                SectionTitle(stringResource(R.string.engines))
            }
            item {
                Panel {
                    Column {
                        r.engines.forEachIndexed { i, e ->
                            if (i > 0) HorizontalDivider()
                            ListRow(
                                if (e.usable) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                                engineName(e.engine) + if (r.recommended == e.engine) " · " + stringResource(R.string.recommended) else "",
                                e.reason,
                                tint = if (e.usable) Brand.Ok else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                SectionTitle(stringResource(R.string.capabilities))
            }
            items(r.checks) { c ->
                ListRow(
                    when (c.support) { Support.YES -> Icons.Outlined.CheckCircle; Support.NO -> Icons.Outlined.ErrorOutline; Support.UNKNOWN -> Icons.Outlined.HelpOutline },
                    c.title, c.detail,
                    tint = when (c.support) { Support.YES -> Brand.Ok; Support.NO -> Brand.Error; Support.UNKNOWN -> Brand.Amber },
                )
            }
            item {
                Spacer(Modifier.height(12.dp))
                Row { TextButton(onClick = onAbout) { Text(stringResource(R.string.about) + " · " + stringResource(R.string.open_source_licenses)) } }
                ListRow(Icons.Outlined.Info, stringResource(R.string.limitations_title), stringResource(R.string.limitations_text), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

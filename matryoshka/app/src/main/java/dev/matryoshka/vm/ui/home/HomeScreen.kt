package dev.matryoshka.vm.ui.home

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.matryoshka.vm.R
import dev.matryoshka.vm.core.model.VmConfig
import dev.matryoshka.vm.core.model.VmState
import dev.matryoshka.vm.service.VmService
import dev.matryoshka.vm.ui.components.EmptyState
import dev.matryoshka.vm.ui.components.NestedDevice
import dev.matryoshka.vm.ui.components.StateChip
import dev.matryoshka.vm.ui.apkSourceOf
import dev.matryoshka.vm.ui.engineName
import dev.matryoshka.vm.ui.manager
import dev.matryoshka.vm.ui.stateText
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    onApps: (String) -> Unit,
    onFiles: (String) -> Unit,
    onSettings: (String) -> Unit,
) {
    val m = manager()
    val vms by m.repo.vms.collectAsState()
    val states by m.states.collectAsState()
    val context = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(Modifier.statusBarsPadding().padding(top = 24.dp, bottom = 8.dp, start = 4.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
                    Text(
                        stringResource(R.string.home_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (vms.isEmpty()) {
                item {
                    EmptyState(stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_text)) {
                        Button(onClick = onCreate) {
                            Icon(Icons.Outlined.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.create_device))
                        }
                    }
                }
            }
            items(vms, key = { it.id }) { vm ->
                DeviceCard(
                    vm = vm,
                    state = states[vm.id] ?: VmState.Stopped,
                    onStart = { VmService.start(context, vm.id); onOpen(vm.id) },
                    onStop = { VmService.stop(context, vm.id) },
                    onOpen = { onOpen(vm.id) },
                    onApps = { onApps(vm.id) },
                    onFiles = { onFiles(vm.id) },
                    onSettings = { onSettings(vm.id) },
                )
            }
        }
        if (vms.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text(stringResource(R.string.new_device)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            )
        }
    }
}

@Composable
private fun DeviceCard(
    vm: VmConfig,
    state: VmState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
    onApps: () -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit,
) {
    val accent = Color(vm.accentColor)
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            NestedDevice(accent, state, Modifier.size(width = 72.dp, height = 132.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StateChip(state)
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, null) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.apps)) }, leadingIcon = { Icon(Icons.Outlined.Apps, null) },
                                onClick = { menu = false; onApps() }, enabled = state is VmState.Running)
                            DropdownMenuItem(text = { Text(stringResource(R.string.files)) }, leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                                onClick = { menu = false; onFiles() }, enabled = state is VmState.Running)
                            DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                onClick = { menu = false; onSettings() })
                        }
                    }
                }
                Text(vm.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Android 11 · ${engineName(vm.engine)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.resources_line, vm.memoryMb / 1024f, vm.cpuCores, vm.storageGb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AnimatedVisibility(state is VmState.Preparing || state is VmState.Booting) {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent)
                        Spacer(Modifier.height(4.dp))
                        Text(stateText(state), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (state is VmState.Failed) {
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        state is VmState.Running -> {
                            Button(onClick = onOpen) {
                                Icon(Icons.Outlined.Visibility, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.open))
                            }
                            FilledTonalButton(onClick = onStop) {
                                Icon(Icons.Outlined.PowerSettingsNew, null, Modifier.size(18.dp))
                            }
                        }
                        state.isActive -> FilledTonalButton(onClick = onStop) { Text(stringResource(R.string.cancel)) }
                        else -> Button(onClick = onStart) {
                            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.start))
                        }
                    }
                }
            }
        }
    }
}

/** Shown when an APK is opened/shared with Matryoshka from the host. */
@Composable
fun InstallSharedApkDialog(uri: Uri, onDone: () -> Unit) {
    val m = manager()
    val context = LocalContext.current
    val vms by m.repo.vms.collectAsState()
    val states by m.states.collectAsState()
    val running = vms.filter { states[it.id] is VmState.Running }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text(stringResource(R.string.install_apk_title)) },
        text = {
            Column {
                when {
                    status != null -> Text(status!!)
                    running.isEmpty() -> Text(stringResource(R.string.install_apk_no_vm))
                    else -> Text(stringResource(R.string.install_apk_pick))
                }
                if (busy) {
                    Spacer(Modifier.height(12.dp)); LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (status == null && !busy) {
                    running.forEach { vm ->
                        TextButton(onClick = {
                            busy = true
                            scope.launch {
                                status = runCatching {
                                    m.guest(vm.id)!!.install(listOf(context.apkSourceOf(uri)))
                                    context.getString(R.string.install_done, vm.name)
                                }.getOrElse { context.getString(R.string.install_failed, it.message) }
                                busy = false
                            }
                        }) { Text(vm.name) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone, enabled = !busy) { Text(stringResource(R.string.close)) } },
    )
}

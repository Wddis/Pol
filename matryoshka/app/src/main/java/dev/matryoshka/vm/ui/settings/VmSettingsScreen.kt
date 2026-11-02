package dev.matryoshka.vm.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.LayersClear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import dev.matryoshka.vm.core.guest.Components
import dev.matryoshka.vm.core.model.EngineType
import dev.matryoshka.vm.core.model.GpuMode
import dev.matryoshka.vm.core.model.VmState
import dev.matryoshka.vm.ui.BackButton
import dev.matryoshka.vm.ui.components.ListRow
import dev.matryoshka.vm.ui.components.Panel
import dev.matryoshka.vm.ui.components.SectionTitle
import dev.matryoshka.vm.ui.create.LabeledSlider
import dev.matryoshka.vm.ui.manager
import dev.matryoshka.vm.ui.theme.Brand
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VmSettingsScreen(vmId: String, onBack: () -> Unit, onDeleted: () -> Unit) {
    val m = manager()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val vm = m.repo.vms.collectAsState().value.firstOrNull { it.id == vmId } ?: return
    val state = m.states.collectAsState().value[vmId] ?: VmState.Stopped
    val running = state is VmState.Running
    val locked = state.isActive

    var name by remember { mutableStateOf(vm.name) }
    var ram by remember { mutableFloatStateOf(vm.memoryMb / 1024f) }
    var cpus by remember { mutableFloatStateOf(vm.cpuCores.toFloat()) }
    var storage by remember { mutableFloatStateOf(vm.storageGb.toFloat()) }
    var gpu by remember { mutableStateOf(vm.gpu) }
    var keepAlive by remember { mutableStateOf(vm.keepAliveInBackground) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmComponent by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    fun save() = scope.launch {
        m.repo.update(vmId) {
            it.copy(
                name = name.trim().ifEmpty { it.name },
                memoryMb = (ram * 1024).roundToInt(), cpuCores = cpus.roundToInt(),
                // Storage can only grow: shrinking a sparse disk would corrupt the guest filesystem.
                storageGb = maxOf(it.storageGb, storage.roundToInt()),
                gpu = gpu, keepAliveInBackground = keepAlive,
            )
        }
        snack.showSnackbar(context.getString(if (locked) R.string.saved_next_boot else R.string.saved))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) }, navigationIcon = { BackButton(onBack) },
                actions = { TextButton(onClick = { save() }) { Text(stringResource(R.string.save)) } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text(stringResource(R.string.device_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

            SectionTitle(stringResource(R.string.resources))
            Panel {
                Column(Modifier.padding(16.dp)) {
                    LabeledSlider(stringResource(R.string.memory), "%.1f GB".format(ram), ram, 1.5f..12f) { ram = (it * 2).roundToInt() / 2f }
                    LabeledSlider(stringResource(R.string.cpu_cores), cpus.roundToInt().toString(), cpus, 1f..Runtime.getRuntime().availableProcessors().toFloat().coerceAtLeast(2f)) { cpus = it.roundToInt().toFloat() }
                    LabeledSlider(stringResource(R.string.storage), "${storage.roundToInt()} GB", storage, vm.storageGb.toFloat()..256f) { storage = (it / 4).roundToInt() * 4f }
                    if (locked) Text(stringResource(R.string.applies_next_boot), style = MaterialTheme.typography.bodySmall, color = Brand.Amber)
                }
            }

            if (vm.engine != EngineType.CONTAINER) {
                SectionTitle(stringResource(R.string.graphics))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    GpuMode.entries.forEachIndexed { i, g ->
                        SegmentedButton(selected = gpu == g, onClick = { gpu = g }, shape = SegmentedButtonDefaults.itemShape(i, GpuMode.entries.size)) {
                            Text(stringResource(when (g) { GpuMode.AUTO -> R.string.gpu_auto; GpuMode.HOST_ACCELERATED -> R.string.gpu_host; GpuMode.SOFTWARE -> R.string.gpu_soft }))
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.behavior))
            Panel {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.keep_alive), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.keep_alive_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(keepAlive, { keepAlive = it })
                }
            }

            SectionTitle(stringResource(R.string.system_components))
            Panel {
                Column {
                    val hasMagisk = m.hasMagiskBoot(vmId)
                    ListRow(
                        Icons.Outlined.AdminPanelSettings,
                        stringResource(if (hasMagisk) R.string.magisk_installed else R.string.install_magisk),
                        stringResource(if (vm.engine == EngineType.CONTAINER) R.string.magisk_container_note else R.string.magisk_install_desc),
                        onClick = if (running && !working && vm.engine != EngineType.CONTAINER) ({ confirmComponent = "magisk" }) else null,
                    )
                    if (hasMagisk) {
                        ListRow(Icons.Outlined.LayersClear, stringResource(R.string.remove_magisk), stringResource(R.string.remove_magisk_desc),
                            tint = Brand.Coral, onClick = { m.removeMagisk(vmId); scope.launch { snack.showSnackbar(context.getString(R.string.saved_next_boot)) } })
                    }
                    HorizontalDivider()
                    ListRow(
                        Icons.Outlined.Hub, stringResource(R.string.install_microg), stringResource(R.string.microg_desc),
                        tint = Brand.Teal, onClick = if (running && !working) ({ confirmComponent = "microg" }) else null,
                    )
                    if (!running) Text(stringResource(R.string.start_first), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 12.dp))
                }
            }

            SectionTitle(stringResource(R.string.about_device))
            Panel {
                Column {
                    ListRow(Icons.Outlined.DeveloperBoard, stringResource(R.string.device_id), vm.id, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { confirmDelete = true },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.Icon(Icons.Outlined.Delete, null)
                Text("  " + stringResource(R.string.delete_device))
            }
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }

    confirmComponent?.let { which ->
        val c = if (which == "magisk") Components.MAGISK else Components.MICROG_CORE
        AlertDialog(
            onDismissRequest = { confirmComponent = null },
            title = { Text(c.title) },
            text = { Text(stringResource(R.string.component_license, c.title, c.license, c.homepage)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmComponent = null
                    working = true
                    scope.launch {
                        val r = runCatching { if (which == "magisk") m.installMagisk(vmId) else m.installMicroG(vmId) }
                        working = false
                        snack.showSnackbar(r.fold({ context.getString(R.string.component_done, c.title) }, { it.message ?: "Error" }))
                    }
                }) { Text(stringResource(R.string.download_install)) }
            },
            dismissButton = { TextButton(onClick = { confirmComponent = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_device_q, vm.name)) },
            text = { Text(stringResource(R.string.delete_device_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { m.delete(vmId); onDeleted() }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

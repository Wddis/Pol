package dev.matryoshka.vm.ui.create

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.matryoshka.vm.R
import dev.matryoshka.vm.core.host.HostReport
import dev.matryoshka.vm.core.model.DisplayConfig
import dev.matryoshka.vm.core.model.EngineType
import dev.matryoshka.vm.core.model.GmsMode
import dev.matryoshka.vm.core.model.GpuMode
import dev.matryoshka.vm.core.model.VmConfig
import dev.matryoshka.vm.ui.BackButton
import dev.matryoshka.vm.ui.components.Panel
import dev.matryoshka.vm.ui.components.SectionTitle
import dev.matryoshka.vm.ui.components.SelectableCard
import dev.matryoshka.vm.ui.components.formatBytes
import dev.matryoshka.vm.ui.engineDescription
import dev.matryoshka.vm.ui.engineName
import dev.matryoshka.vm.ui.gmsName
import dev.matryoshka.vm.ui.manager
import dev.matryoshka.vm.ui.theme.Brand
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class DisplayPreset(val label: Int, val config: DisplayConfig)

private val presets = listOf(
    DisplayPreset(R.string.preset_phone_fhd, DisplayConfig(1080, 2340, 420)),
    DisplayPreset(R.string.preset_phone_hd, DisplayConfig(720, 1560, 300)),
    DisplayPreset(R.string.preset_tablet, DisplayConfig(1600, 2560, 320)),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateVmScreen(onBack: () -> Unit, onCreated: (String) -> Unit, onGetImages: () -> Unit) {
    val m = manager()
    val scope = rememberCoroutineScope()
    val images by m.images.installed.collectAsState()
    var report by remember { mutableStateOf<HostReport?>(null) }
    LaunchedEffect(Unit) { report = m.hostReport(askRoot = true) }

    val count = m.repo.vms.collectAsState().value.size
    var name by remember { mutableStateOf("Android ${count + 1}") }
    var engine by remember { mutableStateOf<EngineType?>(null) }
    LaunchedEffect(report) { if (engine == null) engine = report?.recommended ?: EngineType.HYPERVISOR }
    var imageId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(images, engine) {
        if (imageId == null || images.none { it.id == imageId }) imageId = images.firstOrNull { engine in it.engines }?.id
    }
    val hostRamGb = ((report?.totalRamMb ?: 8192) / 1024f)
    val maxRam = (hostRamGb * 0.6f).coerceIn(2f, 12f)
    var ramGb by remember { mutableFloatStateOf(3f) }
    val maxCpu = (report?.cpuCores ?: 8).coerceAtLeast(2)
    var cpus by remember { mutableFloatStateOf(4f) }
    var storage by remember { mutableFloatStateOf(16f) }
    var preset by remember { mutableStateOf(presets.first()) }
    var gms by remember { mutableStateOf(GmsMode.MICROG) }
    var magisk by remember { mutableStateOf(true) }
    var accent by remember { mutableStateOf(Brand.accents[count % Brand.accents.size]) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.new_device)) }, navigationIcon = { BackButton(onBack) }) },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) },
                label = { Text(stringResource(R.string.device_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Brand.accents.forEach { c ->
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                            .clickableNoRipple { accent = c },
                        contentAlignment = Alignment.Center,
                    ) { if (accent == c) Icon(Icons.Outlined.CheckCircle, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                }
            }

            SectionTitle(stringResource(R.string.virtualization))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EngineType.entries.forEach { e ->
                    val avail = report?.engines?.firstOrNull { it.engine == e }
                    SelectableCard(selected = engine == e, onClick = { engine = e }) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(engineName(e), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                when {
                                    avail == null -> Text("…", style = MaterialTheme.typography.labelMedium)
                                    report?.recommended == e -> Badge(stringResource(R.string.recommended), Brand.Ok)
                                    !avail.usable -> Badge(stringResource(R.string.unavailable), MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(engineDescription(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (avail != null && !avail.usable) {
                                Text(avail.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.system_image))
            val compatible = images.filter { engine in it.engines }
            if (compatible.isEmpty()) {
                Panel {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.no_images), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onGetImages) {
                            Icon(Icons.Outlined.CloudDownload, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.get_images))
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    compatible.forEach { img ->
                        SelectableCard(selected = imageId == img.id, onClick = { imageId = img.id }) {
                            Column {
                                Text(img.name, style = MaterialTheme.typography.titleMedium)
                                Text("Android ${img.androidVersion} · ${img.abi} · ${formatBytes(img.totalSize)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.resources))
            Panel {
                Column(Modifier.padding(16.dp)) {
                    LabeledSlider(stringResource(R.string.memory), "%.1f GB".format(ramGb), ramGb, 1.5f..maxRam) { ramGb = (it * 2).roundToInt() / 2f }
                    LabeledSlider(stringResource(R.string.cpu_cores), cpus.roundToInt().toString(), cpus, 1f..maxCpu.toFloat()) { cpus = it.roundToInt().toFloat() }
                    LabeledSlider(stringResource(R.string.storage), "${storage.roundToInt()} GB", storage, 4f..128f) { storage = (it / 4).roundToInt() * 4f }
                    Text(stringResource(R.string.storage_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionTitle(stringResource(R.string.display))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { p ->
                    FilterChip(selected = preset == p, onClick = { preset = p },
                        label = { Text(stringResource(p.label) + " · ${p.config.width}×${p.config.height}") })
                }
            }

            SectionTitle(stringResource(R.string.google_services))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                GmsMode.entries.forEachIndexed { i, g ->
                    SegmentedButton(selected = gms == g, onClick = { gms = g }, shape = SegmentedButtonDefaults.itemShape(i, GmsMode.entries.size)) {
                        Text(gmsName(g), maxLines = 1)
                    }
                }
            }
            Text(
                stringResource(when (gms) {
                    GmsMode.NONE -> R.string.gms_none_desc
                    GmsMode.MICROG -> R.string.gms_microg_desc
                    GmsMode.USER_PROVIDED_GAPPS -> R.string.gms_user_desc
                }),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )

            SectionTitle(stringResource(R.string.root))
            Panel {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.magisk_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(if (engine == EngineType.CONTAINER) R.string.magisk_container_note else R.string.magisk_desc),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = magisk && engine != EngineType.CONTAINER, onCheckedChange = { magisk = it }, enabled = engine != EngineType.CONTAINER)
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                enabled = name.isNotBlank() && engine != null && imageId != null,
                onClick = {
                    scope.launch {
                        val id = m.repo.newId()
                        m.create(
                            VmConfig(
                                id = id, name = name.trim(), engine = engine!!, imageId = imageId!!,
                                memoryMb = (ramGb * 1024).roundToInt(), cpuCores = cpus.roundToInt(), storageGb = storage.roundToInt(),
                                display = preset.config, gpu = GpuMode.AUTO, gms = gms,
                                magisk = magisk && engine != EngineType.CONTAINER,
                                netIndex = m.repo.nextNetIndex(), accentColor = accent,
                            )
                        )
                        onCreated(id)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(stringResource(R.string.create_device)) }
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = color,
        modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun LabeledSlider(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
    Slider(
        value = v.coerceIn(range), onValueChange = onChange, valueRange = range,
        colors = androidx.compose.material3.SliderDefaults.colors(
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    return this.clickable(interactionSource = source, indication = null, onClick = onClick)
}

package dev.matryoshka.vm.ui.device

import android.app.Activity
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VolumeDown
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.matryoshka.vm.R
import dev.matryoshka.vm.core.display.DisplaySession
import dev.matryoshka.vm.core.model.EngineType
import dev.matryoshka.vm.core.model.VmState
import dev.matryoshka.vm.core.nest.NestDisplayView
import dev.matryoshka.vm.core.nest.NestGuest
import dev.matryoshka.vm.service.VmService
import dev.matryoshka.vm.ui.BackButton
import dev.matryoshka.vm.ui.components.NestedDevice
import dev.matryoshka.vm.ui.components.Panel
import dev.matryoshka.vm.ui.components.SectionTitle
import dev.matryoshka.vm.ui.components.StateChip
import dev.matryoshka.vm.ui.engineName
import dev.matryoshka.vm.ui.gmsName
import dev.matryoshka.vm.ui.manager
import dev.matryoshka.vm.ui.stateText
import dev.matryoshka.vm.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DeviceScreen(
    vmId: String,
    onBack: () -> Unit,
    onApps: () -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit,
) {
    val m = manager()
    val states by m.states.collectAsState()
    val state = states[vmId] ?: VmState.Stopped
    val engine = m.repo.vms.collectAsState().value.firstOrNull { it.id == vmId }?.engine
    // A Nest guest draws its own screen from the first boot frame on (boot animation included).
    val nest = engine == EngineType.NEST
    val live = state is VmState.Running || (nest && state is VmState.Booting && NestGuest.isRunning)
    Crossfade(live, label = "device") { on ->
        when {
            on && nest -> NestLiveDisplay(vmId, state is VmState.Running, onBack, onApps, onFiles, onSettings)
            on -> LiveDisplay(vmId, onBack, onApps, onFiles, onSettings)
            else -> DeviceOverview(vmId, state, onBack, onSettings)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceOverview(vmId: String, state: VmState, onBack: () -> Unit, onSettings: () -> Unit) {
    val m = manager()
    val context = LocalContext.current
    val vm = m.repo.vms.collectAsState().value.firstOrNull { it.id == vmId } ?: return
    val logs = m.logs.collectAsState().value[vmId].orEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm.name) }, navigationIcon = { BackButton(onBack) },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, null) } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            NestedDevice(Color(vm.accentColor), state, Modifier.size(width = 150.dp, height = 280.dp))
            Spacer(Modifier.height(20.dp))
            StateChip(state)
            Spacer(Modifier.height(8.dp))
            Text(stateText(state), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            when {
                state.isActive -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = { VmService.stop(context, vmId) }) { Text(stringResource(R.string.cancel)) }
                }
                else -> Button(onClick = { VmService.start(context, vmId) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.start_device))
                }
            }
            if (state is VmState.Failed && state.log != null) {
                SectionTitle(stringResource(R.string.engine_log), Modifier.fillMaxWidth())
                Panel { Text(state.log, style = Mono, modifier = Modifier.padding(14.dp)) }
            }
            SectionTitle(stringResource(R.string.overview), Modifier.fillMaxWidth())
            Panel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoLine(stringResource(R.string.virtualization), engineName(vm.engine))
                    InfoLine(stringResource(R.string.memory), "%.1f GB".format(vm.memoryMb / 1024f))
                    InfoLine(stringResource(R.string.cpu_cores), vm.cpuCores.toString())
                    InfoLine(stringResource(R.string.storage), "${vm.storageGb} GB")
                    InfoLine(stringResource(R.string.display), "${vm.display.width}×${vm.display.height} · ${vm.display.dpi} dpi")
                    InfoLine(stringResource(R.string.google_services), gmsName(vm.gms))
                    InfoLine(stringResource(R.string.network), "${vm.guestIp} (NAT)")
                }
            }
            if (logs.isNotEmpty()) {
                SectionTitle(stringResource(R.string.activity), Modifier.fillMaxWidth())
                Panel { Text(logs.takeLast(12).joinToString("\n"), style = Mono, modifier = Modifier.padding(14.dp)) }
            }
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }
}

@Composable
private fun InfoLine(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(v, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LiveDisplay(vmId: String, onBack: () -> Unit, onApps: () -> Unit, onFiles: () -> Unit, onSettings: () -> Unit) {
    val m = manager()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<DisplaySession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var surfaceView by remember { mutableStateOf<GuestSurfaceView?>(null) }
    var toolbar by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }

    // Immersive while the virtual device is on screen.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (context as Activity).window
        val ctl = WindowCompat.getInsetsController(window, view)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            ctl.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(vmId) {
        val s = m.openDisplay(vmId)
        if (s == null) { error = context.getString(R.string.display_failed); return@LaunchedEffect }
        runCatching { withContext(Dispatchers.IO) { s.start() } }
            .onSuccess { session = s }
            .onFailure { error = it.message }
    }
    DisposableEffect(Unit) { onDispose { session?.stop() } }

    val info = session?.info?.collectAsState()?.value
    val streamError = session?.error?.collectAsState()?.value

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val s = session
        if (s != null && info != null) {
            AndroidView(
                factory = { ctx -> GuestSurfaceView(ctx, s).also { surfaceView = it } },
                modifier = Modifier.align(Alignment.Center).aspectRatio(info.width.toFloat() / info.height, matchHeightConstraintsFirst = info.height >= info.width),
            )
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (error == null) CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(error ?: stringResource(R.string.connecting_display), color = Color.White.copy(alpha = 0.8f))
            }
        }
        streamError?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(24.dp))
        }

        // Floating control pill.
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = { toolbar = !toolbar }, modifier = Modifier.size(28.dp)) {
                Icon(if (toolbar) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp, null, tint = Color.White.copy(alpha = 0.7f))
            }
            AnimatedVisibility(toolbar, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                Surface(shape = CircleShape, color = Color(0xE6181A22), tonalElevation = 6.dp) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        PillButton(Icons.AutoMirrored.Outlined.ArrowBack) { session?.key(KeyEvent.KEYCODE_BACK) }
                        PillButton(Icons.Outlined.Circle) { session?.key(KeyEvent.KEYCODE_HOME) }
                        PillButton(Icons.Outlined.CropSquare) { session?.key(KeyEvent.KEYCODE_APP_SWITCH) }
                        PillButton(Icons.Outlined.Keyboard) { surfaceView?.toggleKeyboard() }
                        PillButton(Icons.Outlined.ScreenRotation) {
                            scope.launch {
                                m.guest(vmId)?.shell("settings put system accelerometer_rotation 0; " +
                                    "r=\$(settings get system user_rotation); settings put system user_rotation \$(( (r+1) % 4 ))")
                            }
                        }
                        Box {
                            PillButton(Icons.Outlined.MoreHoriz) { menu = true }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                MenuItem(R.string.apps, Icons.Outlined.Apps) { menu = false; onApps() }
                                MenuItem(R.string.files, Icons.Outlined.Folder) { menu = false; onFiles() }
                                MenuItem(R.string.volume_up, Icons.Outlined.VolumeUp) { session?.key(KeyEvent.KEYCODE_VOLUME_UP) }
                                MenuItem(R.string.volume_down, Icons.Outlined.VolumeDown) { session?.key(KeyEvent.KEYCODE_VOLUME_DOWN) }
                                MenuItem(R.string.settings, Icons.Outlined.Settings) { menu = false; onSettings() }
                                MenuItem(R.string.restart, Icons.Outlined.RestartAlt) { menu = false; m.restart(vmId) }
                                MenuItem(R.string.power_off, Icons.Outlined.PowerSettingsNew) { menu = false; VmService.stop(context, vmId); onBack() }
                                MenuItem(R.string.minimize, Icons.AutoMirrored.Outlined.ArrowBack) { menu = false; onBack() }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Immersive, screen kept on while a virtual device is shown. */
@Composable
private fun ImmersiveEffect() {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (context as Activity).window
        val ctl = WindowCompat.getInsetsController(window, view)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            ctl.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/**
 * The Nest guest's own screen (engine viewer). The guest has its own navigation bar; the app's
 * controls live in a side panel that opens from the handle on the right edge (tap or swipe left).
 */
@Composable
private fun NestLiveDisplay(
    vmId: String, booted: Boolean, onBack: () -> Unit, onApps: () -> Unit, onFiles: () -> Unit, onSettings: () -> Unit,
) {
    val m = manager()
    val context = LocalContext.current
    var view by remember { mutableStateOf<NestDisplayView?>(null) }
    var panel by remember { mutableStateOf(false) }
    ImmersiveEffect()
    // The phone's back gesture: closes the panel, otherwise it is the guest's Back.
    androidx.activity.compose.BackHandler {
        if (panel) panel = false else view?.tapKey(NestDisplayView.KEY_BACK)
    }
    val waiting = stringResource(R.string.nest_waiting)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> NestDisplayView(ctx, NestGuest.stateDir(ctx), waiting).also { view = it } },
            modifier = Modifier.align(Alignment.Center)
                .aspectRatio(NestGuest.displayAspect, matchHeightConstraintsFirst = true),
        )
        if (!booted) {
            // No boot animation in the guest (it costs the boot time): a static note instead.
            Text(
                waiting, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // Edge handle: a dark tab with a light rim and an arrow, visible on light and dark screens.
        val tab = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
        Box(
            Modifier.align(Alignment.CenterEnd).size(width = 26.dp, height = 64.dp)
                .clip(tab)
                .background(Color(0xD9181A22))
                .border(1.dp, Color.White.copy(alpha = 0.55f), tab)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { _, drag -> if (drag < -6f) panel = true }
                }
                .clickableNoIndication { panel = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.nest_panel),
                tint = Color.White, modifier = Modifier.size(22.dp),
            )
        }
        // Scrim + panel
        if (panel) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).clickableNoIndication { panel = false })
        }
        AnimatedVisibility(
            panel, modifier = Modifier.align(Alignment.CenterEnd),
            enter = androidx.compose.animation.slideInHorizontally { it } + fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally { it } + fadeOut(),
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp),
                color = Color(0xF2181A22), tonalElevation = 6.dp,
                modifier = Modifier.pointerInput(Unit) {
                    detectHorizontalDragGestures { _, drag -> if (drag > 6f) panel = false }
                },
            ) {
                Column(Modifier.padding(vertical = 8.dp).width(220.dp).verticalScroll(rememberScrollState())) {
                    PanelItem(Icons.Outlined.Keyboard, R.string.nest_keyboard) { panel = false; view?.toggleKeyboard() }
                    PanelItem(Icons.Outlined.CropSquare, R.string.nest_recents, enabled = booted) {
                        panel = false; m.guestKeyEvent(vmId, KeyEvent.KEYCODE_APP_SWITCH)
                    }
                    PanelItem(Icons.Outlined.VolumeUp, R.string.volume_up) { view?.tapKey(NestDisplayView.KEY_VOLUMEUP) }
                    PanelItem(Icons.Outlined.VolumeDown, R.string.volume_down) { view?.tapKey(NestDisplayView.KEY_VOLUMEDOWN) }
                    PanelItem(Icons.Outlined.Apps, R.string.apps, enabled = booted) { panel = false; onApps() }
                    PanelItem(Icons.Outlined.Folder, R.string.files, enabled = booted) { panel = false; onFiles() }
                    PanelItem(Icons.Outlined.Settings, R.string.settings) { panel = false; onSettings() }
                    PanelItem(Icons.Outlined.RestartAlt, R.string.restart) { panel = false; m.restart(vmId) }
                    PanelItem(Icons.Outlined.PowerSettingsNew, R.string.power_off) { panel = false; VmService.stop(context, vmId); onBack() }
                    PanelItem(Icons.AutoMirrored.Outlined.ArrowBack, R.string.minimize) { panel = false; onBack() }
                }
            }
        }
    }
}

@Composable
private fun PanelItem(icon: ImageVector, label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(14.dp))
        Text(stringResource(label), color = tint, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    return this.clickable(interactionSource = source, indication = null, onClick = onClick)
}

@Composable
private fun PillButton(icon: ImageVector, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, null, tint = Color.White) }
}

@Composable
private fun MenuItem(label: Int, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

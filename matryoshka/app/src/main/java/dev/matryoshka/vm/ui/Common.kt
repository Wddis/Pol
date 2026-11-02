package dev.matryoshka.vm.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.matryoshka.vm.MatryoshkaApp
import dev.matryoshka.vm.R
import dev.matryoshka.vm.core.VmManager
import dev.matryoshka.vm.core.model.EngineType
import dev.matryoshka.vm.core.model.GmsMode
import dev.matryoshka.vm.core.model.VmState

@Composable
fun manager(): VmManager = (LocalContext.current.applicationContext as MatryoshkaApp).vm

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
}

@Composable
fun engineName(e: EngineType): String = stringResource(
    when (e) {
        EngineType.HYPERVISOR -> R.string.engine_hypervisor
        EngineType.CONTAINER -> R.string.engine_container
        EngineType.EMULATOR -> R.string.engine_emulator
    }
)

@Composable
fun engineDescription(e: EngineType): String = stringResource(
    when (e) {
        EngineType.HYPERVISOR -> R.string.engine_hypervisor_desc
        EngineType.CONTAINER -> R.string.engine_container_desc
        EngineType.EMULATOR -> R.string.engine_emulator_desc
    }
)

@Composable
fun gmsName(g: GmsMode): String = stringResource(
    when (g) {
        GmsMode.NONE -> R.string.gms_none
        GmsMode.MICROG -> R.string.gms_microg
        GmsMode.USER_PROVIDED_GAPPS -> R.string.gms_user
    }
)

@Composable
fun stateText(s: VmState): String = when (s) {
    VmState.Stopped -> stringResource(R.string.state_off)
    is VmState.Preparing -> s.step
    is VmState.Booting -> s.step
    is VmState.Running -> stringResource(R.string.state_running)
    VmState.Stopping -> stringResource(R.string.state_stopping)
    is VmState.Failed -> s.message
}

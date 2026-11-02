package dev.matryoshka.vm.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.matryoshka.vm.MatryoshkaApp
import dev.matryoshka.vm.core.model.EngineType
import dev.matryoshka.vm.core.model.GmsMode
import dev.matryoshka.vm.core.model.VmConfig
import dev.matryoshka.vm.core.model.VmState
import dev.matryoshka.vm.ui.create.CreateVmScreen
import dev.matryoshka.vm.ui.device.DeviceScreen
import dev.matryoshka.vm.ui.home.HomeScreen
import dev.matryoshka.vm.ui.settings.VmSettingsScreen
import dev.matryoshka.vm.ui.system.SystemScreen
import dev.matryoshka.vm.ui.theme.MatryoshkaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real screens on the JVM and writes PNGs to build/outputs/roborazzi/ — a visual
 * check of the UI without a device. Run: ./gradlew testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi")
class ScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<MatryoshkaApp>()

    @Before fun seed() = runBlocking {
        val m = app.vm
        val img = File(app.filesDir, "images/mvm-a11").apply { mkdirs() }
        File(img, "manifest.json").writeText(
            """{"id":"mvm-a11","name":"Android 11 · Matryoshka build","engines":["HYPERVISOR","EMULATOR","CONTAINER"],
               "files":[{"name":"system.img","sha256":"00","size":1900000000},{"name":"rootfs.tar.gz","sha256":"00","size":820000000}]}"""
        )
        m.images.refresh()
        if (m.repo.vms.value.isEmpty()) {
            m.create(VmConfig("vm-work", "Work phone", EngineType.HYPERVISOR, "mvm-a11", memoryMb = 4096, cpuCores = 4, storageGb = 32, netIndex = 1, accentColor = 0xFF7C5CFF, createdAt = 1))
            m.create(VmConfig("vm-test", "Test lab", EngineType.CONTAINER, "mvm-a11", memoryMb = 3072, cpuCores = 2, storageGb = 16, gms = GmsMode.NONE, netIndex = 2, accentColor = 0xFF2EC4B6, createdAt = 2))
            m.create(VmConfig("vm-games", "Games", EngineType.EMULATOR, "mvm-a11", memoryMb = 2048, cpuCores = 4, storageGb = 24, netIndex = 3, accentColor = 0xFFFF7A59, createdAt = 3))
        }
        m.setState("vm-work", VmState.Running(System.currentTimeMillis()))
        m.setState("vm-test", VmState.Booting("Starting system services"))
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { MatryoshkaTheme(dark = true) { content() } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun home() = shot("01-home") { HomeScreen({}, {}, {}, {}, {}) }
    @Test fun create() = shot("02-create") { CreateVmScreen({}, {}, {}) }
    @Test fun deviceOverview() = shot("03-device") { DeviceScreen("vm-games", {}, {}, {}, {}) }
    @Test fun settings() = shot("04-settings") { VmSettingsScreen("vm-work", {}, {}) }
    @Test fun system() = shot("05-system") { SystemScreen(onAbout = {}) }
    @Test fun nestCheck() = shot("06-nest-check") { dev.matryoshka.vm.ui.system.NestCheckScreen(onBack = {}) }
}

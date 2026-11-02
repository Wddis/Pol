package dev.matryoshka.vm.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.matryoshka.vm.BuildConfig
import dev.matryoshka.vm.R
import dev.matryoshka.vm.ui.BackButton
import dev.matryoshka.vm.ui.components.Panel
import dev.matryoshka.vm.ui.components.SectionTitle

private data class Notice(val name: String, val license: String, val how: String, val url: String)

private val notices = listOf(
    Notice("scrcpy server ${BuildConfig.SCRCPY_SERVER_VERSION}", "Apache-2.0", "Bundled, unmodified; runs inside the guest", "https://github.com/Genymobile/scrcpy"),
    Notice("AndroidX, Jetpack Compose, Material 3", "Apache-2.0", "Linked", "https://developer.android.com/jetpack"),
    Notice("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache-2.0", "Linked", "https://kotlinlang.org"),
    Notice("Material Symbols / Icons", "Apache-2.0", "Linked", "https://fonts.google.com/icons"),
    Notice("AOSP (guest system)", "Apache-2.0 and others", "Guest image, built from source (guest/)", "https://source.android.com"),
    Notice("Linux kernel (guest)", "GPL-2.0", "Guest image; source offer in guest/README", "https://kernel.org"),
    Notice("crosvm", "BSD-3-Clause", "Host's own AVF copy or optional engine pack", "https://crosvm.dev"),
    Notice("QEMU", "GPL-2.0", "Optional separate engine-pack APK, never in this APK", "https://www.qemu.org"),
    Notice("Magisk", "GPL-3.0", "Optional, downloaded from its official releases on request", "https://github.com/topjohnwu/Magisk"),
    Notice("microG", "Apache-2.0", "Optional, downloaded from its official releases on request", "https://microg.org"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.about)) }, navigationIcon = { BackButton(onBack) }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp)) {
            item {
                Text("${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionTitle(stringResource(R.string.open_source_licenses))
            }
            items(notices) { n ->
                Panel(Modifier.padding(bottom = 10.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(n.name, style = MaterialTheme.typography.titleSmall)
                        Text("${n.license} · ${n.how}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(n.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item {
                Text(stringResource(R.string.trademark_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

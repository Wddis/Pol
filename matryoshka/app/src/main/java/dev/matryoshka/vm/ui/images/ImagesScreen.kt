package dev.matryoshka.vm.ui.images

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.matryoshka.vm.BuildConfig
import dev.matryoshka.vm.R
import dev.matryoshka.vm.core.image.DownloadProgress
import dev.matryoshka.vm.core.image.GuestImage
import dev.matryoshka.vm.core.model.EngineType
import dev.matryoshka.vm.ui.components.ListRow
import dev.matryoshka.vm.ui.components.Panel
import dev.matryoshka.vm.ui.components.SectionTitle
import dev.matryoshka.vm.ui.components.formatBytes
import dev.matryoshka.vm.ui.manager
import kotlinx.coroutines.launch

@Composable
fun ImagesScreen() {
    val m = manager()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed by m.images.installed.collectAsState()
    var catalogUrl by remember { mutableStateOf(BuildConfig.DEFAULT_IMAGE_CATALOG) }
    var catalog by remember { mutableStateOf<List<GuestImage>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<DownloadProgress?>(null) }
    var loading by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching { m.images.importBundle(context.contentResolver, uri) { progress = it } }
                .fold({ context.getString(R.string.image_imported, it.name) }, { context.getString(R.string.image_failed, it.message) })
            progress = null
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(top = 8.dp, start = 4.dp)) {
                Text(stringResource(R.string.tab_images), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.images_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            message?.let { Text(it, modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.primary) }
            progress?.let { p ->
                Spacer(Modifier.height(12.dp))
                Text("${p.file} · ${formatBytes(p.done)}" + if (p.total > 0) " / ${formatBytes(p.total)}" else "", style = MaterialTheme.typography.bodySmall)
                if (p.total > 0) LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            SectionTitle(stringResource(R.string.installed))
        }
        if (installed.isEmpty()) item {
            Text(stringResource(R.string.no_images), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
        }
        items(installed, key = { it.id }) { img ->
            Panel(Modifier.padding(bottom = 10.dp)) {
                ListRow(
                    Icons.Outlined.Album, img.name,
                    "Android ${img.androidVersion} · ${img.abi} · ${formatBytes(img.totalSize)}\n" + img.engines.joinToString { engineNameStatic(it) },
                ) {
                    val inUse = m.repo.vms.collectAsState().value.any { it.imageId == img.id }
                    IconButton(enabled = !inUse, onClick = { scope.launch { m.images.delete(img.id) } }) { Icon(Icons.Outlined.Delete, null) }
                }
            }
        }
        item {
            SectionTitle(stringResource(R.string.get_images))
            Panel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(catalogUrl, { catalogUrl = it }, label = { Text(stringResource(R.string.catalog_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !loading, onClick = {
                            loading = true
                            scope.launch {
                                runCatching { m.images.fetchCatalog(catalogUrl).images }
                                    .onSuccess { catalog = it; message = null }
                                    .onFailure { message = context.getString(R.string.catalog_failed, it.message) }
                                loading = false
                            }
                        }) { Icon(Icons.Outlined.CloudDownload, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.browse_catalog)) }
                        OutlinedButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                            Icon(Icons.Outlined.FileOpen, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.import_bundle))
                        }
                    }
                    Text(stringResource(R.string.images_build_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        catalog?.let { list ->
            items(list, key = { "cat-" + it.id }) { img ->
                Panel(Modifier.padding(top = 10.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(img.name, style = MaterialTheme.typography.titleMedium)
                        if (img.description.isNotBlank()) Text(img.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(img.licenseNotice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            img.engines.forEach { e ->
                                OutlinedButton(enabled = progress == null, onClick = {
                                    scope.launch {
                                        message = runCatching { m.images.download(img, e) { progress = it } }
                                            .fold({ context.getString(R.string.image_downloaded, img.name) }, { context.getString(R.string.image_failed, it.message) })
                                        progress = null
                                    }
                                }) { Text("${engineNameStatic(e)} · ${formatBytes(img.filesFor(e).sumOf { it.size })}") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun engineNameStatic(e: EngineType) = when (e) {
    EngineType.HYPERVISOR -> "Hypervisor"
    EngineType.CONTAINER -> "Container"
    EngineType.EMULATOR -> "Emulator"
}

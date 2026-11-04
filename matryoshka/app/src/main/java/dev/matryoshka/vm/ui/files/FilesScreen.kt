package dev.matryoshka.vm.ui.files

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.matryoshka.vm.R
import dev.matryoshka.vm.core.adb.RemoteEntry
import dev.matryoshka.vm.ui.BackButton
import dev.matryoshka.vm.ui.components.ListRow
import dev.matryoshka.vm.ui.components.formatBytes
import dev.matryoshka.vm.ui.describe
import dev.matryoshka.vm.ui.manager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private const val ROOT = "/sdcard"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vmId: String, onBack: () -> Unit) {
    val m = manager()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val guest = remember { m.guest(vmId) }
    val snack = remember { SnackbarHostState() }
    var path by remember { mutableStateOf("$ROOT/Download") }
    var entries by remember { mutableStateOf<List<RemoteEntry>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<RemoteEntry?>(null) }

    suspend fun reload() {
        entries = runCatching { guest!!.list(path) }.getOrElse { snack.showSnackbar(it.message ?: "Error"); emptyList() }
    }
    LaunchedEffect(path) { entries = null; reload() }
    BackHandler(enabled = path != ROOT) { path = path.substringBeforeLast('/').ifEmpty { ROOT } }

    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty() || guest == null) return@rememberLauncherForActivityResult
        scope.launch {
            for (uri in uris) {
                val info = context.describe(uri)
                busy = info.name
                val r = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)!!.use { guest.push(it, "$path/${info.name}") }
                    }
                    guest.scanMedia("$path/${info.name}")
                }
                if (r.isFailure) snack.showSnackbar(context.getString(R.string.transfer_failed, r.exceptionOrNull()?.message))
            }
            busy = null
            reload()
        }
    }
    val download = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val e = pending ?: return@rememberLauncherForActivityResult
        if (uri == null || guest == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = e.name
            val r = runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { guest.pull("$path/${e.name}", it) } }
            }
            busy = null
            snack.showSnackbar(if (r.isSuccess) context.getString(R.string.saved_to_host) else context.getString(R.string.transfer_failed, r.exceptionOrNull()?.message))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.files)) },
                navigationIcon = { BackButton(onBack) },
                actions = { IconButton(onClick = { newFolder = true }) { Icon(Icons.Outlined.CreateNewFolder, null) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { upload.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Outlined.Upload, null) },
                text = { Text(stringResource(R.string.send_to_device)) },
            )
        },
        snackbarHost = { dev.matryoshka.vm.ui.AppSnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // Breadcrumbs
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                val parts = path.removePrefix("/").split('/')
                parts.forEachIndexed { i, p ->
                    val target = "/" + parts.take(i + 1).joinToString("/")
                    AssistChip(onClick = { if (target.length >= ROOT.length) path = target },
                        label = { Text(if (target == ROOT) stringResource(R.string.internal_storage) else p) },
                        modifier = Modifier.padding(end = 6.dp))
                }
            }
            busy?.let {
                Text(stringResource(R.string.transferring, it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp, 4.dp))
            }
            val list = entries
            if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            else LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                if (list.isEmpty()) item {
                    Text(stringResource(R.string.empty_folder), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(list, key = { it.name }) { e ->
                    ListRow(
                        icon = if (e.isDirectory) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
                        title = e.name,
                        subtitle = if (e.isDirectory) null else "${formatBytes(e.size)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.mtime * 1000))}",
                        onClick = { if (e.isDirectory) path = "$path/${e.name}" else { pending = e; download.launch(e.name) } },
                    ) {
                        if (!e.isDirectory) IconButton(onClick = { pending = e; download.launch(e.name) }) { Icon(Icons.Outlined.FileDownload, null) }
                        IconButton(onClick = { scope.launch { guest?.delete("$path/${e.name}"); reload() } }) { Icon(Icons.Outlined.Delete, null) }
                    }
                }
            }
        }
    }

    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text(stringResource(R.string.new_folder)) },
            text = { OutlinedTextField(name, { name = it.replace("/", "") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    newFolder = false
                    scope.launch { guest?.mkdir("$path/$name"); reload() }
                }) { Text(stringResource(R.string.create)) }
            },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

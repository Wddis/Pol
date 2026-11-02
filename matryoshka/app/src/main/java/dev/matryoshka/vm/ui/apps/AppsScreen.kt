package dev.matryoshka.vm.ui.apps

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.matryoshka.vm.R
import dev.matryoshka.vm.core.guest.GuestApp
import dev.matryoshka.vm.core.guest.GuestClient
import dev.matryoshka.vm.ui.BackButton
import dev.matryoshka.vm.ui.apkSourceOf
import dev.matryoshka.vm.ui.manager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class AppMeta(val label: String, val icon: Bitmap?)

/** Labels and icons are read on the host from the guest's APK (pulled once, cached by version). */
private object AppMetaCache {
    private val mem = HashMap<String, AppMeta>()
    private val gate = Semaphore(2)

    suspend fun get(context: Context, guest: GuestClient, app: GuestApp): AppMeta {
        val key = "${app.packageName}@${app.versionCode}"
        synchronized(mem) { mem[key] }?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "apkmeta").apply { mkdirs() }
                val apk = File(dir, "$key.apk")
                val meta = runCatching {
                    if (!apk.exists()) {
                        // Skip huge APKs; a generic icon is fine for them.
                        if (guest.apkSize(app) > 200L * 1024 * 1024) return@runCatching AppMeta(app.packageName, null)
                        guest.pullBaseApk(app, apk)
                    }
                    val pm = context.packageManager
                    val info = pm.getPackageArchiveInfo(apk.path, 0)?.applicationInfo
                        ?: return@runCatching AppMeta(app.packageName, null)
                    info.sourceDir = apk.path
                    info.publicSourceDir = apk.path
                    AppMeta(info.loadLabel(pm).toString(), runCatching { info.loadIcon(pm).toBitmap(96, 96) }.getOrNull())
                }.getOrElse { AppMeta(app.packageName, null) }
                apk.delete() // keep only the extracted metadata
                synchronized(mem) { mem[key] = meta }
                meta
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(vmId: String, onBack: () -> Unit) {
    val m = manager()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val guest = remember { m.guest(vmId) }
    val snack = remember { SnackbarHostState() }
    var apps by remember { mutableStateOf<List<GuestApp>?>(null) }
    var showSystem by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    val labels = remember { mutableStateMapOf<String, String>() }

    suspend fun reload() {
        apps = runCatching { guest!!.listApps(showSystem) }.getOrElse { snack.showSnackbar(it.message ?: "Error"); emptyList() }
    }
    LaunchedEffect(showSystem) { reload() }

    val installer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty() || guest == null) return@rememberLauncherForActivityResult
        scope.launch {
            progress = 0f
            val result = runCatching {
                val sources = uris.map { context.apkSourceOf(it) }
                // Several APKs picked together are treated as one split set when they look like one.
                val asSplitSet = sources.size > 1 && sources.any { it.name.startsWith("split_") || it.name.contains("config.") }
                if (asSplitSet) guest.install(sources) { progress = it }
                else sources.forEachIndexed { i, s -> guest.install(listOf(s)) { p -> progress = (i + p) / sources.size } }
            }
            progress = null
            snack.showSnackbar(result.fold({ context.getString(R.string.installed_ok) }, { context.getString(R.string.install_failed, it.message) }))
            reload()
        }
    }

    var exportPkg by remember { mutableStateOf<GuestApp?>(null) }
    var exportSplit by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val app = exportPkg ?: return@rememberLauncherForActivityResult
        if (uri == null || guest == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use { out ->
                        if (!exportSplit) {
                            guest.exportApk(app.packageName) { _ -> NonClosing(out) }
                        } else {
                            // Split APKs are exported as one .apks archive (zip of all parts).
                            ZipOutputStream(out).use { zip ->
                                guest.exportApk(app.packageName) { name ->
                                    zip.putNextEntry(ZipEntry(name))
                                    object : java.io.FilterOutputStream(zip) {
                                        override fun write(b: ByteArray, off: Int, len: Int) = zip.write(b, off, len)
                                        override fun close() = zip.closeEntry()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            snack.showSnackbar(r.fold({ context.getString(R.string.exported_ok) }, { context.getString(R.string.export_failed, it.message) }))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.apps)) },
                navigationIcon = { BackButton(onBack) },
                actions = { IconButton(onClick = { scope.launch { reload() } }) { Icon(Icons.Outlined.Refresh, null) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { installer.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
                icon = { Icon(Icons.Outlined.InstallMobile, null) },
                text = { Text(stringResource(R.string.import_apk)) },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text(stringResource(R.string.search_apps)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = MaterialTheme.shapes.extraLarge,
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showSystem, onClick = { showSystem = false }, label = { Text(stringResource(R.string.user_apps)) })
                FilterChip(selected = showSystem, onClick = { showSystem = true }, label = { Text(stringResource(R.string.all_apps)) })
            }
            progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
            val list = apps
            if (list == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            } else {
                val q = query.trim().lowercase()
                val filtered = list.filter { q.isEmpty() || it.packageName.contains(q) || labels[it.packageName]?.lowercase()?.contains(q) == true }
                if (filtered.isEmpty()) {
                    Text(stringResource(R.string.no_apps), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            guest = guest!!,
                            onLabel = { labels[app.packageName] = it },
                            onLaunch = { scope.launch { guest.launch(app.packageName) } },
                            onExport = {
                                scope.launch {
                                    val parts = guest.shell("pm path ${app.packageName}").second.lines().count { it.startsWith("package:") }
                                    exportPkg = app
                                    exportSplit = parts > 1
                                    val base = (labels[app.packageName] ?: app.packageName).replace(Regex("[^\\w.\\- ]"), "_")
                                    exporter.launch(base + if (exportSplit) ".apks" else ".apk")
                                }
                            },
                            onUninstall = { scope.launch { guest.uninstall(app.packageName); reload() } },
                            onClear = { scope.launch { guest.clearData(app.packageName); snack.showSnackbar(context.getString(R.string.data_cleared)) } },
                            onToggle = { scope.launch { guest.setEnabled(app.packageName, !app.enabled); reload() } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: GuestApp,
    guest: GuestClient,
    onLabel: (String) -> Unit,
    onLaunch: () -> Unit,
    onExport: () -> Unit,
    onUninstall: () -> Unit,
    onClear: () -> Unit,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    var meta by remember(app.packageName, app.versionCode) { mutableStateOf<AppMeta?>(null) }
    LaunchedEffect(app.packageName, app.versionCode) {
        val loaded = AppMetaCache.get(context, guest, app)
        onLabel(loaded.label)
        meta = loaded
    }
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            val icon = meta?.icon
            if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(40.dp))
            else Icon(Icons.Outlined.Android, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(meta?.label ?: app.packageName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                app.packageName + if (!app.enabled) " · " + stringResource(R.string.disabled) else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onLaunch) { Icon(Icons.Outlined.Launch, stringResource(R.string.open)) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, null) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.export_apk)) }, leadingIcon = { Icon(Icons.Outlined.FileDownload, null) }, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text(stringResource(R.string.clear_data)) }, leadingIcon = { Icon(Icons.Outlined.CleaningServices, null) }, onClick = { menu = false; onClear() })
                DropdownMenuItem(text = { Text(stringResource(if (app.enabled) R.string.disable else R.string.enable)) }, leadingIcon = { Icon(Icons.Outlined.Block, null) }, onClick = { menu = false; onToggle() })
                if (!app.isSystem) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.uninstall)) }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; onUninstall() })
                }
            }
        }
    }
}

/** Lets the export helper "close" per-file streams without closing the SAF stream. */
private class NonClosing(out: java.io.OutputStream) : java.io.FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun close() = flush()
}

package dev.matryoshka.vm.ui.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.matryoshka.vm.R
import dev.matryoshka.vm.core.guest.ApkSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** An app installed on the phone itself, ready to be copied into the guest (base + split APKs). */
data class HostApp(val label: String, val packageName: String, val icon: Bitmap?, val apks: List<File>, val isSystem: Boolean) {
    val size: Long get() = apks.sumOf { it.length() }

    fun sources(): List<ApkSource> = apks.map { f ->
        val name = if (f.name == "base.apk") "$packageName.apk" else f.name
        ApkSource(name, f.length()) { f.inputStream() }
    }
}

/** The phone's apps (the APK files are world-readable on Android, no permission is needed). */
suspend fun loadHostApps(context: Context): List<HostApp> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    pm.getInstalledApplications(0)
        .filter { it.packageName != context.packageName && it.sourceDir != null }
        .mapNotNull { ai ->
            val files = (listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())).map(::File)
            if (files.any { !it.canRead() }) return@mapNotNull null
            HostApp(
                label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(ai.packageName),
                packageName = ai.packageName,
                icon = runCatching { ai.loadIcon(pm).toBitmap(72, 72) }.getOrNull(),
                apks = files,
                isSystem = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == ApplicationInfo.FLAG_SYSTEM,
            )
        }
        .sortedBy { it.label.lowercase() }
}

@Composable
fun HostAppsDialog(onDismiss: () -> Unit, onInstall: (List<HostApp>) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<HostApp>?>(null) }
    var query by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) { apps = loadHostApps(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_from_phone)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_apps)) }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                val list = apps
                if (list == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp))
                } else {
                    val q = query.trim().lowercase()
                    // Apps the user installed first; preinstalled ones only when searched for.
                    val shown = list.filter { (q.isEmpty() && !it.isSystem) || (q.isNotEmpty() && (it.label.lowercase().contains(q) || it.packageName.contains(q))) }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(shown, key = { it.packageName }) { app ->
                            val on = app.packageName in chosen
                            Row(
                                Modifier.fillMaxWidth().clickable { if (on) chosen.remove(app.packageName) else chosen.add(app.packageName) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = on, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                app.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(36.dp)) }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "%.1f MB".format(app.size / 1e6) + if (app.apks.size > 1) " · ${app.apks.size} APK" else "",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = {
                val sel = apps.orEmpty().filter { it.packageName in chosen }
                onInstall(sel)
            }) { Text(stringResource(R.string.install_selected, chosen.size)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

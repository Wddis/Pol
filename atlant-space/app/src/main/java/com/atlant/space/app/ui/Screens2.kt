package com.atlant.space.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atlant.space.engine.diag.CheckStatus
import com.atlant.space.engine.pm.AppSettings
import com.atlant.space.engine.perm.PermState
import java.io.File

@Composable
private fun RowToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.TextPrimary, fontSize = 15.sp)
            Text(subtitle, color = Palette.TextSecondary, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(
            checkedThumbColor = Palette.Bg, checkedTrackColor = Palette.Accent,
            uncheckedThumbColor = Palette.TextSecondary, uncheckedTrackColor = Palette.Surface,
            checkedBorderColor = Palette.Accent, uncheckedBorderColor = Palette.Divider,
        ))
    }
}

@Composable
fun AppDetailScreen(state: AppState, pkg: String, navigate: (Screen) -> Unit, back: () -> Unit) {
    val app = state.app(pkg)
    var confirmUninstall by remember { mutableStateOf(false) }
    var settings by remember(pkg) { mutableStateOf(app?.record?.settings ?: AppSettings()) }
    val rooted = remember(pkg, state.rootGrants()) { state.rootGrants().any { it.pkg == pkg && it.enabled } }

    Column(Modifier.fillMaxSize()) {
        TopBar(app?.label ?: pkg, onBack = back)
        if (app == null) { EmptyState("App not found", "It may have been uninstalled."); return }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AtlantCard(Modifier.fillMaxWidth(), elevated = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Monogram(app.label)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, color = Palette.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text("v${app.record.versionName} (${app.record.versionCode})", color = Palette.TextSecondary, fontSize = 12.sp)
                            Text(app.packageName, color = Palette.TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { state.launch(pkg) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.Bg)) { Text("Open") }
                    MiniButton("Permissions") { navigate(Screen.Permissions(pkg)) }
                }
            }
            item {
                val u = state.packageUsage(pkg)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Code", humanBytes(u.apps), Modifier.weight(1f))
                    StatTile("Data", humanBytes(u.data), Modifier.weight(1f))
                    StatTile("Media", humanBytes(u.shared), Modifier.weight(1f))
                }
            }
            item { SectionHeader("Behaviour") }
            item {
                AtlantCard(Modifier.fillMaxWidth()) {
                    Column {
                        RowToggle("Keep alive", "Keep this app's processes in the background", settings.keepAlive) {
                            settings = settings.copy(keepAlive = it); state.updateAppSettings(pkg, settings)
                        }
                        RowToggle("Wake on broadcast", "Deliver broadcasts while the app is not running", settings.wakeOnBroadcast) {
                            settings = settings.copy(wakeOnBroadcast = it); state.updateAppSettings(pkg, settings)
                        }
                        RowToggle("Native path redirect", "Redirect hard-coded storage paths in native code", settings.nativeRedirect) {
                            settings = settings.copy(nativeRedirect = it); state.updateAppSettings(pkg, settings)
                        }
                        RowToggle("Virtual root", "Give this app a sandboxed su (never real root)", rooted) {
                            state.setRoot(pkg, it)
                        }
                    }
                }
            }
            item { SectionHeader("Maintenance") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MiniButton("Clear cache") { state.clearCache(pkg) }
                    MiniButton("Clear data") { state.clearData(pkg) }
                    MiniButton("Uninstall", danger = true) { confirmUninstall = true }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (confirmUninstall) ConfirmDialog(
        "Uninstall ${app?.label}?", "The app and its data in this space will be removed.",
        "Uninstall", danger = true,
    ) { yes -> confirmUninstall = false; if (yes) { state.uninstall(pkg); back() } }
}

@Composable
fun PermissionsScreen(state: AppState, pkg: String, back: () -> Unit) {
    var perms by remember(pkg) { mutableStateOf(state.permissions(pkg)) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Permissions", onBack = back)
        if (perms.isEmpty()) { EmptyState("No runtime permissions", "This app requests none that need a decision."); return }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Spacer(Modifier.height(4.dp)) }
            items(perms.entries.toList(), key = { it.key }) { (perm, stateVal) ->
                AtlantCard(Modifier.fillMaxWidth()) {
                    Column {
                        Text(perm.removePrefix("android.permission."), color = Palette.TextPrimary, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PermChip("Allow", stateVal == PermState.GRANTED) { set(state, pkg, perm, PermState.GRANTED); perms = state.permissions(pkg) }
                            PermChip("Ask", stateVal == PermState.ASK) { set(state, pkg, perm, PermState.ASK); perms = state.permissions(pkg) }
                            PermChip("Deny", stateVal == PermState.DENIED) { set(state, pkg, perm, PermState.DENIED); perms = state.permissions(pkg) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun set(state: AppState, pkg: String, perm: String, s: PermState) = state.setPermission(pkg, perm, s)

@Composable
private fun PermChip(label: String, selected: Boolean, onClick: () -> Unit) {
    MiniButton(if (selected) "● $label" else label, onClick = onClick)
}

@Composable
fun JournalScreen(state: AppState, back: () -> Unit) {
    LaunchedEffect(Unit) { state.refreshLog() }
    Column(Modifier.fillMaxSize()) {
        TopBar("Journal", onBack = back, action = { MiniButton("Clear") { state.clearLog() } })
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Spacer(Modifier.height(4.dp)) }
            items(state.log) { e ->
                val color = when (e.level.name) { "ERROR" -> Palette.Danger; "WARN" -> Palette.Warn; else -> Palette.TextSecondary }
                AtlantCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row {
                            Text(e.tag, color = Palette.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Text(e.level.name, color = color, fontSize = 11.sp)
                        }
                        Text(e.message, color = Palette.TextPrimary, fontSize = 13.sp)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun FileManagerScreen(state: AppState, back: () -> Unit) {
    val roots = remember { state.fileRoots() }
    var dir by remember { mutableStateOf(roots.firstOrNull()?.second) }
    val rootPaths = remember { roots.map { it.second.path } }
    Column(Modifier.fillMaxSize()) {
        TopBar("File manager", onBack = back)
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            roots.forEach { (name, f) -> MiniButton(name) { dir = f } }
        }
        val d = dir
        if (d == null) { EmptyState("No space selected", "Pick a space first."); return }
        val children = remember(d.path) { d.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }).orEmpty() }
        val canUp = d.path !in rootPaths && rootPaths.any { d.path.startsWith(it) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(d.path, color = Palette.TextSecondary, fontSize = 11.sp)
            }
            if (canUp) item {
                AtlantCard(Modifier.fillMaxWidth().clickable { dir = d.parentFile }) {
                    Text("..", color = Palette.Accent, fontSize = 15.sp)
                }
            }
            items(children, key = { it.path }) { f ->
                AtlantCard(Modifier.fillMaxWidth().clickable { if (f.isDirectory) dir = f }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (f.isDirectory) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile, null,
                            tint = if (f.isDirectory) Palette.Accent else Palette.TextSecondary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, color = Palette.TextPrimary, fontSize = 14.sp)
                            if (f.isFile) Text(humanBytes(f.length()), color = Palette.TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
            if (children.isEmpty()) item { Text("Empty", color = Palette.TextSecondary, fontSize = 13.sp) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun RootManagerScreen(state: AppState, back: () -> Unit) {
    val apps = state.apps
    val grants = state.rootGrants()
    Column(Modifier.fillMaxSize()) {
        TopBar("Root manager", onBack = back)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                AtlantCard(Modifier.fillMaxWidth(), elevated = true) {
                    Text("Virtual root gives an app a sandboxed su that works only inside its space. " +
                        "The host is never really rooted and no real UID 0 is granted.",
                        color = Palette.TextSecondary, fontSize = 13.sp)
                }
            }
            if (apps.isEmpty()) item { Text("Install an app first.", color = Palette.TextSecondary, fontSize = 14.sp) }
            items(apps, key = { it.packageName }) { app ->
                val on = grants.any { it.pkg == app.packageName && it.enabled }
                AtlantCard(Modifier.fillMaxWidth()) {
                    RowToggle(app.label, app.packageName, on) { state.setRoot(app.packageName, it) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun BackupsScreen(state: AppState, back: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    val backups = remember(tick) { state.backups() }
    Column(Modifier.fillMaxSize()) {
        TopBar("Backups", onBack = back, action = { MiniButton("Export current") { state.exportSpace(); tick++ } })
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Spacer(Modifier.height(4.dp)) }
            if (backups.isEmpty()) item { Text("No backups yet. Export the current space to create one.", color = Palette.TextSecondary, fontSize = 14.sp) }
            items(backups, key = { it.file.path }) { b ->
                AtlantCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.spaceName, color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("${b.file.name} · ${humanBytes(b.size)}", color = Palette.TextSecondary, fontSize = 12.sp)
                        }
                        MiniButton("Restore") { state.restoreBackup(b) }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun DiagnosticsScreen(state: AppState, back: () -> Unit) {
    val report by produceState(initialValue = null as com.atlant.space.engine.diag.DiagnosticsReport?) {
        value = state.diagnostics()
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Diagnostics", onBack = back)
        val r = report
        if (r == null) { EmptyState("Running checks…", "Inspecting the virtualization environment."); return }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Spacer(Modifier.height(4.dp)) }
            items(r.checks) { c ->
                val color = when (c.status) { CheckStatus.OK -> Palette.Accent; CheckStatus.WARN -> Palette.Warn; CheckStatus.FAIL -> Palette.Danger }
                AtlantCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.status.name, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(10.dp))
                            Text(c.name, color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(c.detail, color = Palette.TextSecondary, fontSize = 13.sp)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun SpaceSettingsScreen(state: AppState, back: () -> Unit) {
    val space = state.selectedSpace
    Column(Modifier.fillMaxSize()) {
        TopBar("Space settings", onBack = back)
        if (space == null) { EmptyState("No space", "Create a space first."); return }
        var rename by remember { mutableStateOf(false) }
        var killBg by remember(space.id) { mutableStateOf(space.policy.killInBackground) }
        var maxProc by remember(space.id) { mutableStateOf(space.policy.maxProcesses) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AtlantCard(Modifier.fillMaxWidth(), elevated = true) {
                    Column {
                        Text(space.name, color = Palette.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Android ID ${space.env.androidId}", color = Palette.TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MiniButton("Rename") { rename = true }
                            MiniButton("New identity") { state.regenerateIdentity(space.id) }
                        }
                    }
                }
            }
            item { SectionHeader("Resource policy") }
            item {
                AtlantCard(Modifier.fillMaxWidth()) {
                    Column {
                        RowToggle("Kill in background", "Stop this space's processes when it goes to background", killBg) {
                            killBg = it; state.setPolicy(space.id, space.policy.copy(killInBackground = it, maxProcesses = maxProc))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Max concurrent processes: $maxProc", color = Palette.TextPrimary, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MiniButton("−") { if (maxProc > 1) { maxProc--; state.setPolicy(space.id, space.policy.copy(maxProcesses = maxProc, killInBackground = killBg)) } }
                            MiniButton("+") { if (maxProc < 8) { maxProc++; state.setPolicy(space.id, space.policy.copy(maxProcesses = maxProc, killInBackground = killBg)) } }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        if (rename) TextPromptDialog("Rename space", "Name", space.name) { name ->
            rename = false; if (name != null) state.renameSpace(space.id, name)
        }
    }
}

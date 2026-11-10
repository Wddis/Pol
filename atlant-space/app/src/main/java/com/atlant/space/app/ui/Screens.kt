package com.atlant.space.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atlant.space.engine.pm.InstallProgress
import com.atlant.space.engine.pm.InstalledApp
import com.atlant.space.engine.space.Space

/** A back-navigation header shared by every pushed screen. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Palette.TextPrimary)
            }
        } else Spacer(Modifier.width(8.dp))
        Text(title, color = Palette.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(start = 4.dp))
        action?.invoke()
    }
}

/** Space picker chip row used on content screens. */
@Composable
private fun SpaceSelector(state: AppState) {
    val active = state.spaces.filter { !it.archived }
    if (active.size <= 1) return
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        active.forEach { s ->
            val selected = s.id == state.selectedSpaceId
            AssistChip(
                onClick = { state.selectSpace(s.id) },
                label = { Text(s.name) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (selected) Palette.SurfaceElevated else Palette.Surface,
                    labelColor = if (selected) Palette.Accent else Palette.TextSecondary,
                ),
            )
        }
    }
}

@Composable
fun HomeScreen(state: AppState, navigate: (Screen) -> Unit, switchTab: (Screen) -> Unit) {
    LaunchedEffect(Unit) { state.refreshProcesses() }
    val space = state.selectedSpace
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.height(8.dp)); AtlantLogo() }
        item {
            AtlantCard(elevated = true, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("Current space", color = Palette.TextSecondary, fontSize = 11.sp, letterSpacing = 1.5.sp)
                    Text(space?.name ?: "—", color = Palette.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    space?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("ID ${it.env.androidId}", color = Palette.TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Apps", state.apps.size.toString(), Modifier.weight(1f), accent = true)
                StatTile("Spaces", state.spaces.count { !it.archived }.toString(), Modifier.weight(1f))
                StatTile("Running", state.processes.size.toString(), Modifier.weight(1f))
            }
        }
        item {
            AtlantCard(Modifier.fillMaxWidth()) {
                Column {
                    val u = state.usage
                    Text("Storage used", color = Palette.TextSecondary, fontSize = 11.sp, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(humanBytes(u.total), color = Palette.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("apps ${humanBytes(u.apps)}  ·  data ${humanBytes(u.data)}  ·  media ${humanBytes(u.shared)}",
                        color = Palette.TextSecondary, fontSize = 12.sp)
                }
            }
        }
        item { SectionHeader("Quick start") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { switchTab(Screen.Apps) }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.Bg)) {
                    Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("Install app")
                }
                Button(onClick = { switchTab(Screen.Spaces) }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Surface, contentColor = Palette.TextPrimary)) {
                    Text("Manage spaces")
                }
            }
        }
        item {
            if (state.apps.isNotEmpty()) SectionHeader("Installed apps")
        }
        items(state.apps, key = { it.packageName }) { app ->
            AppRow(app, onClick = { navigate(Screen.AppDetail(app.packageName)) }, onLaunch = { state.launch(app.packageName) })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun AppsScreen(state: AppState, navigate: (Screen) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) state.installApks(uris)
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Applications", action = {
            IconButton(onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) }) {
                Icon(Icons.Outlined.Add, contentDescription = "Install", tint = Palette.Accent)
            }
        })
        Column(Modifier.padding(horizontal = 16.dp)) { SpaceSelector(state) }
        if (state.apps.isEmpty()) {
            EmptyState("No apps in this space yet", "Tap + to install an APK, split APK or XAPK bundle.")
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Spacer(Modifier.height(4.dp)) }
                items(state.apps, key = { it.packageName }) { app ->
                    AppRow(app, onClick = { navigate(Screen.AppDetail(app.packageName)) }, onLaunch = { state.launch(app.packageName) })
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
fun AppRow(app: InstalledApp, onClick: () -> Unit, onLaunch: () -> Unit) {
    AtlantCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Monogram(app.label)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, color = Palette.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("${app.packageName} · v${app.record.versionName}", color = Palette.TextSecondary, fontSize = 12.sp)
            }
            Button(onClick = onLaunch, colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.Bg)) {
                Text("Open")
            }
        }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Palette.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(subtitle, color = Palette.TextSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
fun MoreScreen(state: AppState, navigate: (Screen) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar("More")
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val items = listOf(
                Triple("Space settings", Icons.Outlined.Settings, Screen.SpaceSettings),
                Triple("File manager", Icons.Outlined.Folder, Screen.FileManager),
                Triple("Root manager", Icons.Outlined.Shield, Screen.RootManager),
                Triple("Backups", Icons.Outlined.Backup, Screen.Backups),
                Triple("Diagnostics", Icons.Outlined.BugReport, Screen.Diagnostics),
                Triple("Journal", Icons.Outlined.History, Screen.Journal),
            )
            item { Spacer(Modifier.height(4.dp)) }
            items(items) { (label, icon, screen) ->
                AtlantCard(Modifier.fillMaxWidth().clickable { navigate(screen) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = Palette.Accent)
                        Spacer(Modifier.width(14.dp))
                        Text(label, color = Palette.TextPrimary, fontSize = 16.sp)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)); AtlantLogo(compact = true) }
            item { Text("No root · no bootloader unlock · in-process virtualization",
                color = Palette.TextSecondary, fontSize = 12.sp) }
        }
    }
}

@Composable
fun InstallDialog(install: InstallState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (install.done) onDismiss() },
        containerColor = Palette.SurfaceElevated,
        title = { Text(if (install.error != null) "Install failed" else if (install.done) "Installed" else "Installing", color = Palette.TextPrimary) },
        text = {
            Column {
                when {
                    install.error != null -> Text(install.error, color = Palette.Danger)
                    install.done -> Text("${install.label} is ready.", color = Palette.TextPrimary)
                    else -> {
                        val p = install.progress
                        Text(stageLabel(p?.stage), color = Palette.TextSecondary)
                        Spacer(Modifier.height(10.dp))
                        Meter(p?.fraction ?: 0f)
                    }
                }
            }
        },
        confirmButton = {
            if (install.done) TextButton(onClick = onDismiss) { Text("OK", color = Palette.Accent) }
        },
    )
}

private fun stageLabel(stage: InstallProgress.Stage?): String = when (stage) {
    InstallProgress.Stage.COPYING -> "Copying…"
    InstallProgress.Stage.UNPACKING -> "Unpacking bundle…"
    InstallProgress.Stage.VERIFYING -> "Verifying…"
    InstallProgress.Stage.NATIVE_LIBS -> "Extracting native libraries…"
    InstallProgress.Stage.COMMITTING -> "Finishing…"
    null -> "Preparing…"
}

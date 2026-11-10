package com.atlant.space.app.ui

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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import com.atlant.space.engine.proc.MemoryInfo
import com.atlant.space.engine.space.Space

@Composable
fun SpacesScreen(state: AppState) {
    var createOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Space?>(null) }
    var cloning by remember { mutableStateOf<Space?>(null) }
    var deleting by remember { mutableStateOf<Space?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Spaces", action = {
            IconButton(onClick = { createOpen = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "New space", tint = Palette.Accent)
            }
        })
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Spacer(Modifier.height(4.dp)) }
            items(state.spaces, key = { it.id }) { s ->
                val selected = s.id == state.selectedSpaceId
                AtlantCard(Modifier.fillMaxWidth(), elevated = selected) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.name, color = Palette.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                    if (s.archived) {
                                        Spacer(Modifier.width(8.dp))
                                        Text("ARCHIVED", color = Palette.Warn, fontSize = 10.sp, letterSpacing = 1.sp)
                                    }
                                }
                                Text("ID ${s.env.androidId} · max ${s.policy.maxProcesses} proc",
                                    color = Palette.TextSecondary, fontSize = 12.sp)
                            }
                            if (selected) Text("ACTIVE", color = Palette.Accent, fontSize = 11.sp, letterSpacing = 1.5.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!selected) MiniButton("Select") { state.selectSpace(s.id) }
                            MiniButton("Rename") { editing = s }
                            MiniButton("Clone") { cloning = s }
                            MiniButton(if (s.archived) "Restore" else "Archive") { state.archiveSpace(s.id, !s.archived) }
                            MiniButton("Delete", danger = true) { deleting = s }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (createOpen) TextPromptDialog("New space", "Name", "") { name ->
        createOpen = false
        if (name != null) state.createSpace(name)
    }
    editing?.let { s ->
        TextPromptDialog("Rename space", "Name", s.name) { name ->
            editing = null
            if (name != null) state.renameSpace(s.id, name)
        }
    }
    cloning?.let { s ->
        TextPromptDialog("Clone space", "New name", "${s.name} copy") { name ->
            cloning = null
            if (name != null) state.cloneSpace(s.id, name)
        }
    }
    deleting?.let { s ->
        ConfirmDialog(
            "Delete ${s.name}?",
            "Every app and all data in this space will be permanently removed.",
            confirm = "Delete", danger = true,
            onResult = { yes -> deleting = null; if (yes) state.deleteSpace(s.id) },
        )
    }
}

@Composable
fun MonitorScreen(state: AppState) {
    LaunchedEffect(Unit) { state.refreshProcesses() }
    val mem = state.memory
    Column(Modifier.fillMaxSize()) {
        TopBar("Process monitor")
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AtlantCard(Modifier.fillMaxWidth(), elevated = true) {
                    Column {
                        Text("Device memory", color = Palette.TextSecondary, fontSize = 11.sp, letterSpacing = 1.5.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("${mem.usedKb / 1024} / ${mem.totalKb / 1024} MB",
                            color = Palette.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Meter(mem.usedFraction)
                        if (mem.lowMemory) {
                            Spacer(Modifier.height(6.dp))
                            Text("Device is low on memory", color = Palette.Warn, fontSize = 12.sp)
                        }
                    }
                }
            }
            item { SectionHeader("Running guest processes") }
            if (state.processes.isEmpty()) {
                item { Text("No guest processes are running.", color = Palette.TextSecondary, fontSize = 14.sp) }
            }
            items(state.processes, key = { it.slot }) { p ->
                val rss = remember(p.pid) { if (p.pid > 0) MemoryInfo.resident(p.pid) else 0L }
                AtlantCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.processName, color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("slot ${p.slot} · space ${p.spaceId} · pid ${p.pid}", color = Palette.TextSecondary, fontSize = 12.sp)
                            if (p.packages.isNotEmpty())
                                Text(p.packages.joinToString(), color = Palette.TextSecondary, fontSize = 12.sp)
                        }
                        Text(if (rss > 0) "${rss / 1024} MB" else "—", color = Palette.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                MiniButton("Force-stop current space", danger = true) { state.forceStop() }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

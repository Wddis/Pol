package com.atlant.space.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/** Everything the UI can show. Tabs live in the bottom bar; the rest is pushed on a back stack. */
sealed class Screen {
    data object Home : Screen()
    data object Spaces : Screen()
    data object Apps : Screen()
    data object Monitor : Screen()
    data object More : Screen()

    data class AppDetail(val pkg: String) : Screen()
    data class Permissions(val pkg: String) : Screen()
    data object Journal : Screen()
    data object FileManager : Screen()
    data object RootManager : Screen()
    data object Backups : Screen()
    data object Diagnostics : Screen()
    data object SpaceSettings : Screen()
}

private data class Tab(val screen: Screen, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Screen.Home, "Home", Icons.Outlined.Dashboard),
    Tab(Screen.Spaces, "Spaces", Icons.Outlined.Layers),
    Tab(Screen.Apps, "Apps", Icons.Outlined.Apps),
    Tab(Screen.Monitor, "Monitor", Icons.Outlined.Monitor),
    Tab(Screen.More, "More", Icons.Outlined.Menu),
)

@Composable
fun AtlantRoot(state: AppState) {
    val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val current = backStack.last()
    val isTab = tabs.any { it.screen == current }

    fun navigate(s: Screen) { backStack.add(s) }
    fun switchTab(s: Screen) { backStack.clear(); backStack.add(s) }
    fun back() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    BackHandler(enabled = backStack.size > 1) { back() }

    val snackbar = remember { SnackbarHostState() }
    val toast = state.toast
    LaunchedEffect(toast?.id) {
        toast ?: return@LaunchedEffect
        snackbar.showSnackbar(toast.message)
        state.consumeToast()
    }

    Scaffold(
        containerColor = Palette.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = Palette.BgSecondary) {
                tabs.forEach { t ->
                    val selected = isTab && current == t.screen
                    NavigationBarItem(
                        selected = selected,
                        onClick = { switchTab(t.screen) },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Palette.Accent,
                            selectedTextColor = Palette.Accent,
                            indicatorColor = Palette.SurfaceElevated,
                            unselectedIconColor = Palette.TextSecondary,
                            unselectedTextColor = Palette.TextSecondary,
                        ),
                    )
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            Route(state, current, ::navigate, ::back, ::switchTab)
        }
    }

    state.installState?.let { InstallDialog(it) { state.dismissInstall() } }
}

@Composable
private fun Route(
    state: AppState,
    screen: Screen,
    navigate: (Screen) -> Unit,
    back: () -> Unit,
    switchTab: (Screen) -> Unit,
) {
    when (screen) {
        Screen.Home -> HomeScreen(state, navigate, switchTab)
        Screen.Spaces -> SpacesScreen(state)
        Screen.Apps -> AppsScreen(state, navigate)
        Screen.Monitor -> MonitorScreen(state)
        Screen.More -> MoreScreen(state, navigate)
        is Screen.AppDetail -> AppDetailScreen(state, screen.pkg, navigate, back)
        is Screen.Permissions -> PermissionsScreen(state, screen.pkg, back)
        Screen.Journal -> JournalScreen(state, back)
        Screen.FileManager -> FileManagerScreen(state, back)
        Screen.RootManager -> RootManagerScreen(state, back)
        Screen.Backups -> BackupsScreen(state, back)
        Screen.Diagnostics -> DiagnosticsScreen(state, back)
        Screen.SpaceSettings -> SpaceSettingsScreen(state, back)
    }
}

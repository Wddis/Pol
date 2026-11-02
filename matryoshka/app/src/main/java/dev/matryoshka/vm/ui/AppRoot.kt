package dev.matryoshka.vm.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.matryoshka.vm.R
import dev.matryoshka.vm.ui.apps.AppsScreen
import dev.matryoshka.vm.ui.create.CreateVmScreen
import dev.matryoshka.vm.ui.device.DeviceScreen
import dev.matryoshka.vm.ui.files.FilesScreen
import dev.matryoshka.vm.ui.home.HomeScreen
import dev.matryoshka.vm.ui.home.InstallSharedApkDialog
import dev.matryoshka.vm.ui.images.ImagesScreen
import dev.matryoshka.vm.ui.settings.AboutScreen
import dev.matryoshka.vm.ui.settings.VmSettingsScreen
import dev.matryoshka.vm.ui.system.SystemScreen

object Routes {
    const val HOME = "home"
    const val IMAGES = "images"
    const val SYSTEM = "system"
    const val CREATE = "create"
    const val ABOUT = "about"
    fun device(id: String) = "device/$id"
    fun apps(id: String) = "apps/$id"
    fun files(id: String) = "files/$id"
    fun settings(id: String) = "settings/$id"
}

private data class Tab(val route: String, val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.tab_devices, Icons.Outlined.PhoneAndroid),
    Tab(Routes.IMAGES, R.string.tab_images, Icons.Outlined.Album),
    Tab(Routes.SYSTEM, R.string.tab_system, Icons.Outlined.Memory),
)

@Composable
fun AppRoot(pendingApk: MutableState<Uri?>) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showTabs = tabs.any { it.route == route }

    Scaffold(
        bottomBar = {
            if (showTabs) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(Routes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        AppNavHost(nav, Modifier.padding(bottom = if (showTabs) padding.calculateBottomPadding() else androidx.compose.ui.unit.Dp(0f)))
    }

    pendingApk.value?.let { uri ->
        InstallSharedApkDialog(uri, onDone = { pendingApk.value = null })
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, modifier: Modifier) {
    val idArg = listOf(navArgument("id") { type = NavType.StringType })
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(320)) + fadeIn(tween(320)) },
        exitTransition = { fadeOut(tween(200)) },
        popEnterTransition = { fadeIn(tween(250)) },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300)) + fadeOut(tween(300)) },
    ) {
        composable(Routes.HOME, enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) {
            HomeScreen(
                onCreate = { nav.navigate(Routes.CREATE) },
                onOpen = { nav.navigate(Routes.device(it)) },
                onApps = { nav.navigate(Routes.apps(it)) },
                onFiles = { nav.navigate(Routes.files(it)) },
                onSettings = { nav.navigate(Routes.settings(it)) },
            )
        }
        composable(Routes.IMAGES, enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) { ImagesScreen() }
        composable(Routes.SYSTEM, enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) {
            SystemScreen(onAbout = { nav.navigate(Routes.ABOUT) })
        }
        composable(Routes.CREATE) {
            CreateVmScreen(
                onBack = { nav.popBackStack() },
                onCreated = { id ->
                    nav.popBackStack()
                    nav.navigate(Routes.device(id))
                },
                onGetImages = { nav.navigate(Routes.IMAGES) },
            )
        }
        composable("device/{id}", idArg) { e ->
            val id = e.arguments!!.getString("id")!!
            DeviceScreen(
                vmId = id,
                onBack = { nav.popBackStack() },
                onApps = { nav.navigate(Routes.apps(id)) },
                onFiles = { nav.navigate(Routes.files(id)) },
                onSettings = { nav.navigate(Routes.settings(id)) },
            )
        }
        composable("apps/{id}", idArg) { e -> AppsScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
        composable("files/{id}", idArg) { e -> FilesScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
        composable("settings/{id}", idArg) { e ->
            VmSettingsScreen(
                e.arguments!!.getString("id")!!,
                onBack = { nav.popBackStack() },
                onDeleted = { nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
    }
}

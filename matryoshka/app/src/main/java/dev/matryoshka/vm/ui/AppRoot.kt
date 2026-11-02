package dev.matryoshka.vm.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.BugReport
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
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
import dev.matryoshka.vm.ui.system.NestCheckScreen
import dev.matryoshka.vm.ui.system.SystemScreen
import dev.matryoshka.vm.BuildConfig
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

object Routes {
    const val HOME = "home"
    const val IMAGES = "images"
    const val SYSTEM = "system"
    const val CREATE = "create"
    const val ABOUT = "about"
    const val NEST_CHECK = "nestcheck"
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
        Box(Modifier.fillMaxSize()) {
            AppNavHost(nav, Modifier.padding(bottom = if (showTabs) padding.calculateBottomPadding() else androidx.compose.ui.unit.Dp(0f)))
            // Temporary tester tool: one tap collects logs and opens the share sheet.
            if (BuildConfig.TESTER_TOOLS && route?.startsWith("device/") != true) {
                TesterLogButton(
                    Modifier.align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = (if (showTabs) padding.calculateBottomPadding() else 0.dp) + 12.dp)
                        .navigationBarsPadding(),
                )
            }
        }
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
            SystemScreen(onAbout = { nav.navigate(Routes.ABOUT) }, onNestCheck = { nav.navigate(Routes.NEST_CHECK) })
        }
        composable(Routes.NEST_CHECK) { NestCheckScreen(onBack = { nav.popBackStack() }) }
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

@Composable
private fun TesterLogButton(modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val m = manager()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.material3.SmallFloatingActionButton(
        onClick = {
            if (busy) return@SmallFloatingActionButton
            busy = true
            scope.launch {
                runCatching { dev.matryoshka.vm.core.debug.TesterLog.share(context, dev.matryoshka.vm.core.debug.TesterLog.collect(context, m)) }
                busy = false
            }
        },
        modifier = modifier,
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.padding(10.dp), strokeWidth = 2.dp)
        else Icon(Icons.Outlined.BugReport, stringResource(R.string.tester_log))
    }
}

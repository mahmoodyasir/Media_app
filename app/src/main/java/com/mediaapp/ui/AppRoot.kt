package com.mediaapp.ui

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.mediaapp.IncomingMedia
import com.mediaapp.data.model.MediaFilter
import com.mediaapp.data.model.SortOrder
import com.mediaapp.editor.EditorLaunchStore
import com.mediaapp.ui.ViewerOrigin
import com.mediaapp.ui.ViewerRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

private object Routes {
    const val Library = "library"
    const val Folders = "folders"
    const val Vault = "vault"
    const val Trash = "trash"
    const val Folder = "folder/{folderKey}"
    const val Viewer = "viewer"
    const val ImageEditor = "image-editor"
    const val VideoEditor = "video-editor"
    fun folder(key: String) = "folder/${RouteCodec.encode(key)}"
}

@HiltViewModel
class RootViewModel @Inject constructor(
    val requests: ViewerRequestStore,
    val launches: EditorLaunchStore,
) : ViewModel()

@Composable
fun MediaAppRoot(incoming: IncomingMedia?, onIncomingConsumed: () -> Unit) {
    val root: RootViewModel = hiltViewModel()
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val viewer = route == Routes.Viewer || route == Routes.ImageEditor || route == Routes.VideoEditor
    val tabs = listOf(
        Tab(Routes.Library, "Library", Icons.Outlined.PhotoLibrary),
        Tab(Routes.Folders, "Folders", Icons.Outlined.Folder),
        Tab(Routes.Vault, "Locked", Icons.Outlined.Lock),
        Tab(Routes.Trash, "Bin", Icons.Outlined.Delete),
    )

    LaunchedEffect(incoming) {
        val open = incoming ?: return@LaunchedEffect
        root.requests.open(
            ViewerRequest(
                origin = ViewerOrigin.External(open.uri, open.mime),
                startId = "external",
                filter = MediaFilter.All,
                sort = SortOrder.Newest,
            ),
        )
        nav.navigate(Routes.Viewer)
        onIncomingConsumed()
    }

    SharedTransitionLayout {
        val sharedScope = this
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!viewer) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = Routes.Library,
                modifier = Modifier.padding(padding),
            ) {
                composable(Routes.Library) {
                    LibraryRoute(
                        snackbar = snackbar,
                        sharedScope = sharedScope,
                        animatedScope = this,
                        onOpenFolder = {},
                        onOpenEntry = { ui, entry ->
                            root.requests.open(ViewerRequest(ViewerOrigin.Library, entry.id, ui.filter, ui.sort))
                            nav.navigate(Routes.Viewer)
                        },
                    )
                }
                composable(Routes.Folders) {
                    LibraryRoute(
                        snackbar = snackbar,
                        sharedScope = sharedScope,
                        animatedScope = this,
                        showFolders = true,
                        onOpenFolder = { key -> nav.navigate(Routes.folder(key)) },
                        onOpenEntry = { _, _ -> },
                    )
                }
                composable(
                    route = Routes.Folder,
                    arguments = listOf(navArgument("folderKey") { type = NavType.StringType }),
                ) {
                    FolderRoute(
                        snackbar = snackbar,
                        sharedScope = sharedScope,
                        animatedScope = this,
                        onOpenEntry = { ui, entry ->
                            val key = entry.folderKey
                            root.requests.open(ViewerRequest(ViewerOrigin.Folder(key), entry.id, ui.filter, ui.sort))
                            nav.navigate(Routes.Viewer)
                        },
                    )
                }
                composable(Routes.Vault) {
                    VaultRoute(
                        snackbar = snackbar,
                        sharedScope = sharedScope,
                        animatedScope = this,
                        onOpenEntry = { ui, entry ->
                            root.requests.open(ViewerRequest(ViewerOrigin.Vault, entry.id, ui.filter, ui.sort))
                            nav.navigate(Routes.Viewer)
                        },
                    )
                }
                composable(Routes.Trash) {
                    TrashRoute(
                        snackbar = snackbar,
                        sharedScope = sharedScope,
                        animatedScope = this,
                        onOpenEntry = { ui, entry ->
                            root.requests.open(ViewerRequest(ViewerOrigin.Trash, entry.id, ui.filter, ui.sort))
                            nav.navigate(Routes.Viewer)
                        },
                    )
                }
                composable(Routes.Viewer) {
                    ViewerRoute(
                        snackbar = snackbar,
                        sharedScope = sharedScope,
                        animatedScope = this,
                        onBack = { nav.popBackStack() },
                        onEdit = { entry ->
                            if (entry.isVideo) {
                                root.launches.video = entry
                                nav.navigate(Routes.VideoEditor)
                            } else {
                                root.launches.image = entry
                                nav.navigate(Routes.ImageEditor)
                            }
                        },
                    )
                }
                composable(Routes.ImageEditor) {
                    ImageEditorRoute(snackbar = snackbar, onBack = { nav.popBackStack() })
                }
                composable(Routes.VideoEditor) {
                    VideoEditorRoute(snackbar = snackbar, onBack = { nav.popBackStack() })
                }
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

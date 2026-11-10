package com.atlant.space.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atlant.space.app.ui.AppState
import com.atlant.space.app.ui.AtlantRoot
import com.atlant.space.app.ui.AtlantTheme

/**
 * The single UI activity of the host app. Everything the user does — spaces, installing apps,
 * launching, monitoring — happens inside this one activity's Compose tree, driven entirely by
 * [AppState]. Guest apps do not run here; they run in the `:vprocN` processes the engine launches.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AtlantTheme {
                val state: AppState = viewModel()
                AtlantRoot(state)
            }
        }
    }
}

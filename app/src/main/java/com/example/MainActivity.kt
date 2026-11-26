package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.engine.configureAppCheck
import com.example.i18n.StringsProvider
import com.example.ui.screens.AntennaScreen
import com.example.ui.screens.DirectorScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LiveFanScreen
import com.example.ui.screens.PilotScreen
import com.example.ui.theme.MetropolitanoMxTheme
import com.example.ui.viewmodel.MxProViewModel
import com.example.ui.viewmodel.MxRoutes
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        setContent {
            val vm: MxProViewModel = viewModel()
            val state by vm.uiState.collectAsStateWithLifecycle()
            val strings = StringsProvider.current(state.languageOverride)
            val navController = rememberNavController()

            MetropolitanoMxTheme(sunMode = state.sunMode) {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
                ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = MxRoutes.HOME
                    ) {
                        composable(MxRoutes.HOME) {
                            HomeScreen(
                                state = state,
                                strings = strings,
                                viewModel = vm,
                                onNavigatePilot = { navController.navigate(MxRoutes.PILOT) },
                                onNavigateLiveFan = { navController.navigate(MxRoutes.LIVE_FAN) },
                                onNavigateAntenna = { navController.navigate(MxRoutes.ANTENNA) },
                                onNavigateDirector = { navController.navigate(MxRoutes.DIRECTOR) }
                            )
                        }
                        composable(MxRoutes.ANTENNA) {
                            AntennaScreen(
                                state = state,
                                strings = strings,
                                viewModel = vm,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(MxRoutes.PILOT) {
                            PilotScreen(
                                state = state,
                                strings = strings,
                                viewModel = vm,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(MxRoutes.DIRECTOR) {
                            DirectorScreen(
                                state = state,
                                strings = strings,
                                viewModel = vm,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(MxRoutes.LIVE_FAN) {
                            LiveFanScreen(
                                state = state,
                                strings = strings,
                                viewModel = vm,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }

                    state.statusBannerMessage?.let { msg ->
                        LaunchedEffect(msg) {
                            delay(3800L)
                            vm.clearBannerMessage()
                        }
                        Snackbar(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp),
                            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                            actionContentColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                            action = {
                                TextButton(onClick = { vm.clearBannerMessage() }) {
                                    Text("OK", color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                                }
                            }
                        ) {
                            Text(msg, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                }
            }
        }
    }
}

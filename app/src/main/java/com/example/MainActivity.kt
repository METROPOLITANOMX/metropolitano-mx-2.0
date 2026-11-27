package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.repository.MxProRepository
import com.example.engine.GoogleAuthScreen
import com.example.engine.attemptAutoSignIn
import com.example.engine.authStateFlow
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
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val appContext = context.applicationContext
            val credentialManager = remember(context) { CredentialManager.create(context) }
            val currentUser by remember { Firebase.auth.authStateFlow() }
                .collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)
            var isCheckingAutoSignIn by remember { mutableStateOf(Firebase.auth.currentUser == null) }

            LaunchedEffect(Unit) {
                if (Firebase.auth.currentUser == null) {
                    attemptAutoSignIn(
                        context = context,
                        credentialManager = credentialManager,
                        onAuthSuccess = { isCheckingAutoSignIn = false },
                        onUnauthenticated = { isCheckingAutoSignIn = false },
                        scope = this
                    )
                } else {
                    isCheckingAutoSignIn = false
                }
            }

            if (currentUser == null) {
                val isSpanish = remember { Locale.getDefault().language == "es" }
                MetropolitanoMxTheme(sunMode = false) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground
                    ) {
                        if (isCheckingAutoSignIn) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            GoogleAuthScreen(
                                onAuthSuccess = { isCheckingAutoSignIn = false },
                                isSpanish = isSpanish
                            )
                        }
                    }
                }
            } else {
                val user = currentUser!!
                key(user.uid) {
                    val db = remember(appContext) {
                        FirebaseFirestore.getInstance(
                            appContext.getString(R.string.firestore_database_id)
                        )
                    }
                    val repo = remember(appContext, db) {
                        MxProRepository(appContext, db, Firebase.auth)
                    }
                    val vm: MxProViewModel = viewModel(
                        key = user.uid,
                        factory = object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                return MxProViewModel(
                                    appContext = appContext,
                                    repo = repo,
                                    currentUserId = user.uid,
                                    userEmail = user.email,
                                    userDisplayName = user.displayName
                                ) as T
                            }
                        }
                    )
                    val state by vm.uiState.collectAsStateWithLifecycle()
                    val strings = StringsProvider.current(state.languageOverride)
                    val navController = rememberNavController()

                    MetropolitanoMxTheme(sunMode = state.sunMode) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background,
                            contentColor = MaterialTheme.colorScheme.onBackground
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
                                            onBack = { navController.popBackStack() },
                                            onNavigatePilot = { navController.navigate(MxRoutes.PILOT) }
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
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                        actionContentColor = MaterialTheme.colorScheme.primary,
                                        action = {
                                            TextButton(onClick = { vm.clearBannerMessage() }) {
                                                Text("OK", color = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    ) {
                                        Text(msg, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

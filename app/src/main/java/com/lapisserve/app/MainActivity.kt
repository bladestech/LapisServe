package com.lapisserve.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import com.lapisserve.app.ui.screens.*
import com.lapisserve.app.ui.theme.LapisTheme

sealed interface Route {
    data object Home : Route
    data object Create : Route
    data object Tuning : Route
    data class Server(val id: String) : Route
}

private fun Route.encode() = when (this) { Route.Home -> "home"; Route.Create -> "create"; Route.Tuning -> "tuning"; is Route.Server -> "server:$id" }
private fun decode(s: String): Route = when {
    s == "create" -> Route.Create; s == "tuning" -> Route.Tuning
    s.startsWith("server:") -> Route.Server(s.removePrefix("server:")); else -> Route.Home
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val manager = LapisApp.get(this).manager
        setContent {
            LapisTheme {
                val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                val stack = rememberSaveable(saver = listSaver<MutableList<Route>, String>(
                    save = { it.map(Route::encode) }, restore = { it.map(::decode).toMutableStateList() })) { mutableStateListOf<Route>(Route.Home) }
                val current = stack.last()
                BackHandler(stack.size > 1) { stack.removeAt(stack.lastIndex) }
                fun push(r: Route) { stack.add(r) }
                fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AnimatedContent(
                        targetState = current, label = "nav",
                        transitionSpec = {
                            val forward = stack.size >= 1 && targetState != Route.Home
                            (fadeIn(tween(220, 80)) + slideInHorizontally(tween(320)) { if (forward) it / 8 else -it / 8 }) togetherWith
                                (fadeOut(tween(120)) + slideOutHorizontally(tween(320)) { if (forward) -it / 8 else it / 8 })
                        },
                    ) { route ->
                        when (route) {
                            Route.Home -> HomeScreen(manager, onOpen = { push(Route.Server(it)) }, onCreate = { push(Route.Create) }, onTuning = { push(Route.Tuning) })
                            Route.Create -> CreateScreen(manager, onBack = ::pop, onCreated = { stack.removeAt(stack.lastIndex); push(Route.Server(it)) })
                            Route.Tuning -> TuningScreen(manager, onBack = ::pop)
                            is Route.Server -> ServerScreen(manager, route.id, onBack = ::pop, onTuning = { push(Route.Tuning) })
                        }
                    }
                }
            }
        }
    }
}

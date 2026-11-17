@file:OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)

package com.lapisserve.app.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.lapisserve.app.engine.ServerManager
import com.lapisserve.app.ui.glass.LapisBackdrop
import com.lapisserve.app.ui.glass.LocalHazeState
import com.lapisserve.app.ui.glass.GlassTabBar
import com.lapisserve.app.ui.glass.TabItem
import com.lapisserve.app.ui.glass.glassBar
import com.lapisserve.app.ui.screens.*
import com.lapisserve.app.ui.theme.AppPrefs
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    SERVERS("Servers", Icons.Rounded.Dns),
    ACTIVITY("Activity", Icons.Rounded.Notifications),
    DEVICE("Device", Icons.Rounded.PhoneAndroid),
    SETTINGS("Settings", Icons.Rounded.Palette),
}

/** Screens pushed on top of the tabs. */
sealed interface Route {
    data object Create : Route
    data class Server(val id: String, val startTab: String = "Overview") : Route
}

private fun Route.encode() = when (this) { Route.Create -> "create"; is Route.Server -> "server:$id:$startTab" }
private fun decode(s: String): Route =
    if (s == "create") Route.Create else s.removePrefix("server:").split(':').let { Route.Server(it[0], it.getOrElse(1) { "Overview" }) }

// Shared-element plumbing: lets a server's avatar fly from its list card into the detail header.
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
fun Modifier.sharedAvatar(serverId: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val anim = LocalAnimScope.current ?: return this
    return with(shared) { this@sharedAvatar.sharedElement(rememberSharedContentState("avatar-$serverId"), anim) }
}

@Composable
fun AppShell(manager: ServerManager, prefs: AppPrefs) {
    val hazeState = remember { HazeState() }
    var tab by rememberSaveable { mutableStateOf(Tab.SERVERS) }
    val stack = rememberSaveable(saver = listSaver<MutableList<Route>, String>(
        save = { it.map(Route::encode) }, restore = { it.map(::decode).toMutableStateList() })) { mutableStateListOf<Route>() }
    BackHandler(stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    BackHandler(stack.isEmpty() && tab != Tab.SERVERS) { tab = Tab.SERVERS }

    fun push(r: Route) { stack.add(r) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        LapisBackdrop {
            // The screen content is the blur source; the nav bar below is its sibling so it can blur what scrolls beneath it.
            Box(Modifier.fillMaxSize().haze(hazeState)) { SharedTransitionLayout {
                AnimatedContent(
                    targetState = stack.lastOrNull(), label = "route",
                    transitionSpec = {
                        val forward = (targetState != null) && (initialState == null || stack.size >= 1)
                        if (forward) (fadeIn(tween(260, 60)) + slideInHorizontally(spring(0.85f, 380f)) { it / 6 }) togetherWith
                            (fadeOut(tween(160)) + slideOutHorizontally(spring(0.85f, 380f)) { -it / 10 })
                        else (fadeIn(tween(260, 60)) + slideInHorizontally(spring(0.85f, 380f)) { -it / 10 }) togetherWith
                            (fadeOut(tween(160)) + slideOutHorizontally(spring(0.85f, 380f)) { it / 6 })
                    },
                ) { route ->
                    CompositionLocalProvider(LocalSharedScope provides this@SharedTransitionLayout, LocalAnimScope provides this@AnimatedContent) {
                        when (route) {
                            null -> TabsHost(manager, prefs, tab, onOpen = { push(Route.Server(it)) }, onCreate = { push(Route.Create) })
                            Route.Create -> CreateScreen(manager, onBack = ::pop, onCreated = { stack.removeAt(stack.lastIndex); push(Route.Server(it)) })
                            is Route.Server -> ServerScreen(manager, route.id, route.startTab, onBack = ::pop, onTuning = { stack.clear(); tab = Tab.DEVICE })
                        }
                    }
                }
            } }
            AnimatedVisibility(
                visible = stack.isEmpty(), modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(spring(0.8f, 420f)) { it } + fadeIn(), exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(120)),
            ) { GlassNavBar(tab, onSelect = { tab = it }) }
        }
    }
}

@Composable
private fun TabsHost(manager: ServerManager, prefs: AppPrefs, tab: Tab, onOpen: (String) -> Unit, onCreate: () -> Unit) {
    AnimatedContent(
        targetState = tab, label = "tabs",
        transitionSpec = {
            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (fadeIn(tween(240, 40)) + slideInHorizontally(spring(0.9f, 500f)) { dir * it / 8 }) togetherWith
                (fadeOut(tween(140)) + slideOutHorizontally(spring(0.9f, 500f)) { -dir * it / 8 })
        },
    ) { t ->
        when (t) {
            Tab.SERVERS -> ServersScreen(manager, onOpen, onCreate)
            Tab.ACTIVITY -> ActivityScreen(manager, onOpen)
            Tab.DEVICE -> DeviceScreen(manager)
            Tab.SETTINGS -> AppSettingsScreen(prefs)
        }
    }
}

@Composable
private fun GlassNavBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val items = remember { Tab.entries.map { TabItem(it.name, it.label, it.icon) } }
    GlassTabBar(items, selected.name, { k -> onSelect(Tab.valueOf(k)) }, Modifier.navigationBarsPadding().padding(bottom = 12.dp))
}

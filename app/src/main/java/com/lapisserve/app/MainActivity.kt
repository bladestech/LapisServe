package com.lapisserve.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lapisserve.app.ui.components.LapisIntro
import com.lapisserve.app.ui.components.LogoColors
import com.lapisserve.app.ui.components.duoLogoColors
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.res.colorResource
import com.lapisserve.app.ui.nav.AppShell
import com.lapisserve.app.ui.theme.LapisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = LapisApp.get(this)
        val coldStart = savedInstanceState == null
        setContent {
            val appearance by app.prefs.appearance.collectAsState()
            LapisTheme(appearance) {
                val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) }
                // The app loads underneath while the logo draws itself once; only on a cold start, not on rotation.
                var intro by rememberSaveable { mutableStateOf(coldStart) }
                Box(Modifier.fillMaxSize()) {
                    AppShell(app.manager, app.prefs)
                    AnimatedVisibility(intro, enter = EnterTransition.None, exit = fadeOut(tween(280))) {
                        // Match Android's own splash (which follows the system theme, not the in-app one) so the
                        // handoff is seamless, then fade into the app in whatever theme it uses.
                        val systemDark = isSystemInDarkTheme()
                        Box(Modifier.fillMaxSize().background(colorResource(R.color.splash_background)), Alignment.Center) {
                            LapisIntro(size = 148.dp, durationMs = 800, colors = if (systemDark) LogoColors.Original else duoLogoColors()) { intro = false }
                        }
                    }
                }
            }
        }
    }
}

package com.lapisserve.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lapisserve.app.ui.components.LapisSplash
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
                    if (intro) {
                        // Matches Android's own splash (which follows the system theme, not the in-app one) so the handoff
                        // is seamless; the wipe then reveals the app in whatever theme it uses.
                        val systemDark = isSystemInDarkTheme()
                        LapisSplash(colorResource(R.color.splash_background), if (systemDark) LogoColors.Original else duoLogoColors()) { intro = false }
                    }
                }
            }
        }
    }
}

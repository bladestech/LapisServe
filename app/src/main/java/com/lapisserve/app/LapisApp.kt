package com.lapisserve.app

import android.app.Application
import android.content.Context
import com.lapisserve.app.engine.ServerManager

class LapisApp : Application() {
    val manager: ServerManager by lazy { ServerManager(this) }
    val prefs: com.lapisserve.app.ui.theme.AppPrefs by lazy { com.lapisserve.app.ui.theme.AppPrefs(this) }

    companion object {
        fun get(ctx: Context) = ctx.applicationContext as LapisApp
    }
}

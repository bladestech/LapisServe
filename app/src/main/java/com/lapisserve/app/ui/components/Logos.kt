package com.lapisserve.app.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.lapisserve.app.core.Http
import com.lapisserve.app.model.ServerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Official project logos are fetched at runtime from each project's own repository and cached on-device,
 * the same way server software is. They are never stored in this repository, so their licensing and
 * trademark terms stay with their owners. Types without an entry (or when offline) fall back to a
 * generated icon.
 *
 * Vanilla is intentionally absent: Mojang publishes no hotlinkable logo and its brand rules restrict
 * reuse of the Minecraft logo.
 */
object Logos {
    private val sources = mapOf(
        ServerType.PAPER to "https://raw.githubusercontent.com/PaperMC/Paper/HEAD/paper-server/src/main/resources/logo.png",
        ServerType.PUMPKIN to "https://raw.githubusercontent.com/Pumpkin-MC/Pumpkin/HEAD/assets/default_icon.png",
    )
    private val mem = ConcurrentHashMap<ServerType, ImageBitmap>()

    suspend fun get(ctx: Context, type: ServerType): ImageBitmap? {
        mem[type]?.let { return it }
        val url = sources[type] ?: return null
        return withContext(Dispatchers.IO) {
            val f = File(ctx.filesDir, "logos/${type.name.lowercase()}.png")
            runCatching {
                if (!f.exists()) { f.parentFile?.mkdirs(); f.writeBytes(Http.bytes(url)) }
                BitmapFactory.decodeFile(f.path)?.asImageBitmap()
            }.getOrNull()?.also { mem[type] = it }
        }
    }
}

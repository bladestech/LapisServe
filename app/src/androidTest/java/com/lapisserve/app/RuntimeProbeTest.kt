package com.lapisserve.app

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.lapisserve.app.core.Arch
import org.junit.Test
import java.io.File

/**
 * Diagnostic: runs the downloaded runtime inside the app's own process context (where Android's
 * seccomp filter applies) and logs how each step ends. Requires a server to have been set up once.
 */
class RuntimeProbeTest {
    private fun run(label: String, env: Map<String, String> = emptyMap(), vararg cmd: String) {
        val r = runCatching {
            val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
            pb.environment().putAll(env)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText().trim().take(300)
            "exit=${p.waitFor()} out=$out"
        }.getOrElse { "failed: $it" }
        Log.i("RuntimeProbe", "$label -> $r")
    }

    @Test fun probe() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val arch = Arch.current!!
        val root = File(ctx.filesDir, "runtimes")
        val loader = File(root, "musl/lib/${arch.muslLoader}").path
        val jre = File(root, "temurin-25")
        val jloader = File(jre, "bin/${arch.muslLoader}").path
        val env = mapOf("LD_LIBRARY_PATH" to listOf("lib/server", "lib", "lib/jli").joinToString(":") { File(jre, it).path } + ":" + File(root, "musl/lib").path + ":" + File(root, "musl/usr/lib").path)

        run("sh echo", emptyMap(), "/system/bin/sh", "-c", "echo ok")
        run("loader --version", emptyMap(), loader, "--version")
        run("loader --list /bin/true-ish (system sh)", emptyMap(), loader, "--list", File(jre, "bin/java").path)
        run("java -version", env, jloader, File(jre, "bin/java").path, "-version")
        run("java -Xint -version", env, jloader, File(jre, "bin/java").path, "-Xint", "-version")
    }
}

package com.nuvio.app.features.plugins.runtime

import android.os.Process
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

internal val pluginDispatcher: CoroutineDispatcher =
    Executors.newFixedThreadPool(MAX_CONCURRENT_PLUGINS) { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "plugin-worker").apply {
            isDaemon = true
        }
    }.asCoroutineDispatcher()

internal fun QuickJs.configurePluginRuntime() {
    evaluationTimeoutMillis = PLUGIN_TIMEOUT_MS
}

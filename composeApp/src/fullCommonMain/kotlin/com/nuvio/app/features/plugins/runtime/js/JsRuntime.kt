package com.nuvio.app.features.plugins.runtime.js

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.quickJs
import com.nuvio.app.features.plugins.runtime.configurePluginRuntime
import com.nuvio.app.features.plugins.runtime.pluginDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.concurrent.Volatile
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

internal class JsRuntime {
    suspend fun <T> use(block: suspend QuickJs.() -> T): T {
        val dispatcher = (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher)
            ?: pluginDispatcher
        return quickJs(dispatcher) {
            configurePluginRuntime()
            block()
        }
    }

    companion object {
        @Volatile
        private var cachedPolyfillBytecode: ByteArray? = null

        @Volatile
        private var cachedCallBytecode: ByteArray? = null

        @Volatile
        private var cachedSettingsCallBytecode: ByteArray? = null

        fun polyfillBytecode(runtime: QuickJs): ByteArray =
            cachedPolyfillBytecode ?: runtime.compile(JsBindings.staticPolyfillCode, "polyfill.js", false).also {
                cachedPolyfillBytecode = it
            }

        fun callBytecode(runtime: QuickJs): ByteArray =
            cachedCallBytecode ?: runtime.compile(JsBindings.staticCallCode, "call.js", false).also {
                cachedCallBytecode = it
            }

        fun settingsCallBytecode(runtime: QuickJs): ByteArray =
            cachedSettingsCallBytecode
                ?: runtime.compile(JsBindings.staticSettingsCallCode, "settings-call.js", false).also {
                    cachedSettingsCallBytecode = it
                }
    }
}

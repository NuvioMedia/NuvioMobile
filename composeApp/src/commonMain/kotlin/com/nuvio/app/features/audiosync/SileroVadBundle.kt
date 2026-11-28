package com.nuvio.app.features.audiosync

import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

internal object SileroVadBundle {
    private const val PATH = "files/silero_vad_v5_16k.bin"

    @Volatile
    private var cached: SileroVadWeights? = null
    private val lock = SynchronizedObject()

    @OptIn(ExperimentalResourceApi::class)
    fun load(): SileroVadWeights {
        cached?.let { return it }
        return synchronized(lock) {
            cached ?: SileroVadWeights.read(runBlocking { Res.readBytes(PATH) }).also { cached = it }
        }
    }
}

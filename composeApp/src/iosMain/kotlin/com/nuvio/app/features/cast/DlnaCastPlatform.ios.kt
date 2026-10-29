package com.nuvio.app.features.cast

internal actual object DlnaCastPlatform {
    actual suspend fun scanDevices(timeoutMs: Int): DlnaScanResult =
        DlnaScanResult.Failure("DLNA nie jest wspierane na iOS w tej wersji")

    actual suspend fun prepareProxyUrl(request: DlnaCastRequest): String =
        throw IllegalStateException("DLNA nie wspierane na iOS")

    actual suspend fun castToDevice(device: DlnaDevice, request: DlnaCastRequest, proxyUrl: String): Boolean = false

    actual suspend fun stopPlayback(device: DlnaDevice): Boolean = false
    actual suspend fun pausePlayback(device: DlnaDevice): Boolean = false
    actual suspend fun resumePlayback(device: DlnaDevice): Boolean = false
    actual suspend fun seekPlayback(device: DlnaDevice, positionMs: Long): Boolean = false
    actual suspend fun getPositionInfo(device: DlnaDevice): Long? = null
    actual suspend fun setVolume(device: DlnaDevice, volumePercent: Int): Boolean = false

    actual suspend fun getLocalIpAddress(): String? = null

    actual fun stopProxy() {}
}

package com.nuvio.app.features.livetv.data

internal actual fun exportVpnBackup(legacySnapshot: String?): String? = com.nuvio.vpn.android.VpnBackup.prepareExport(legacySnapshot)
internal actual fun restoreVpnBackup(value: String): Boolean {
    com.nuvio.vpn.android.VpnBackup.restoreSetup(value)
    return true
}
internal actual fun observeVpnBackupChanges(onChange: () -> Unit) {
    com.nuvio.vpn.android.VpnPlaybackProtection.observeBackupChanges { onChange() }
}

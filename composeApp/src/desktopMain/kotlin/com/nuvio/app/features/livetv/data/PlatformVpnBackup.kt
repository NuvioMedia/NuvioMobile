package com.nuvio.app.features.livetv.data

internal actual fun exportVpnBackup(legacySnapshot: String?): String? = null
internal actual fun restoreVpnBackup(value: String): Boolean = false
internal actual fun observeVpnBackupChanges(onChange: () -> Unit) = Unit

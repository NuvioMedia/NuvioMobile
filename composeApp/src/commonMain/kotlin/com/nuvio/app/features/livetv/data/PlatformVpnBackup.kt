package com.nuvio.app.features.livetv.data

internal expect fun exportVpnBackup(legacySnapshot: String?): String?
// True when native storage owns the restored profiles; otherwise preserve the opaque sync field.
internal expect fun restoreVpnBackup(value: String): Boolean
internal expect fun observeVpnBackupChanges(onChange: () -> Unit)

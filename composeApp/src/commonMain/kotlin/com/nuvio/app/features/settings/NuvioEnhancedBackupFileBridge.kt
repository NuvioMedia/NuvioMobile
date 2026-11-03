package com.nuvio.app.features.settings

internal expect object NuvioEnhancedBackupFileBridge {
    fun exportBackup(
        fileName: String,
        payload: String,
        onResult: (Result<String>) -> Unit,
    )

    fun importBackup(
        onResult: (Result<String>) -> Unit,
    )
}

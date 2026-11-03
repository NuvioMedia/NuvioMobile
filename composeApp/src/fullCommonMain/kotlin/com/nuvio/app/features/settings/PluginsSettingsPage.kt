package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import com.nuvio.app.features.plugins.PluginsSettingsPageContent
import com.nuvio.app.features.plugins.CloudStreamSettingsSection

internal actual fun LazyListScope.pluginsSettingsContent() {
    item {
        PluginsSettingsPageContent(
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item {
        CloudStreamSettingsSection()
    }
}

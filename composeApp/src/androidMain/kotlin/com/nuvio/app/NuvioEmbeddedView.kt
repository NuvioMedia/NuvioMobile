package com.nuvio.app

import android.content.Context
import android.view.View
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.nuvio.app.core.ui.InlineOverlayHost
import com.nuvio.app.core.ui.LocalUseInlineOverlays
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/**
 * The whole Nuvio app as a plain View, for windows that aren't activities (the Android Auto screen draws it in
 * a Presentation). The host window must set the view-tree lifecycle, view-model-store, saved-state and
 * navigation-event owners.
 */
object NuvioEmbeddedView {
    fun create(
        context: Context,
        backOwner: OnBackPressedDispatcherOwner,
        resultOwner: ActivityResultRegistryOwner,
        navigationOwner: NavigationEventDispatcherOwner,
    ): View {
        NuvioAndroidBootstrap.initialize(context)
        return ComposeView(context).apply {
            setContent {
                CompositionLocalProvider(
                    LocalOnBackPressedDispatcherOwner provides backOwner,
                    LocalActivityResultRegistryOwner provides resultOwner,
                    // Needed by Nuvio's NavDisplay (navigation3) for back handling; activities provide it.
                    LocalNavigationEventDispatcherOwner provides navigationOwner,
                    // Dialogs and sheets draw inside this window: Android won't open dialog windows here.
                    LocalUseInlineOverlays provides true,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        App()
                        InlineOverlayHost()
                    }
                }
            }
        }
    }
}

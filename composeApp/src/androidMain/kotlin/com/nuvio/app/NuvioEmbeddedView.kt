package com.nuvio.app

import android.content.Context
import android.view.View
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView

/**
 * The whole Nuvio app as a plain View, for windows that aren't activities (the Android Auto screen draws it in
 * a Presentation). The host window must set the view-tree lifecycle, view-model-store and saved-state owners.
 */
object NuvioEmbeddedView {
    fun create(
        context: Context,
        backOwner: OnBackPressedDispatcherOwner,
        resultOwner: ActivityResultRegistryOwner,
    ): View {
        NuvioAndroidBootstrap.initialize(context)
        return ComposeView(context).apply {
            setContent {
                CompositionLocalProvider(
                    LocalOnBackPressedDispatcherOwner provides backOwner,
                    LocalActivityResultRegistryOwner provides resultOwner,
                ) {
                    App()
                }
            }
        }
    }
}

package com.nuvio.app.features.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.mdblist.mdbListMessageResource
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.providerId
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_create_list
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryListManagementButton() {
    val profile by ProfileRepository.state.collectAsStateWithLifecycle()
    val connections by TrackingProviderRegistry.connectedProviderIds.collectAsStateWithLifecycle()
    val library by LibraryRepository.uiState.collectAsStateWithLifecycle()
    val context = remember(profile, connections, library) { LibraryRepository.listManagementContext() }
    val controller = remember { LibraryListManagementController(LibraryRepository::listManagementContext, LibraryRepository::listManager) }
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(context) { controller.reconcileContext() }
    if (context == null) return
    val provider = context.source.providerId?.let(TrackingProviderRegistry::libraryProvider) ?: return
    val manager = provider.listManager ?: return
    IconButton(onClick = controller::create) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = stringResource(Res.string.library_create_list),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state?.let { dialog ->
        LibraryListManagementDialog(
            state = dialog,
            errorMessage = dialog.error?.takeIf { provider.providerId == TrackingProviderId.MDBLIST }
                ?.let { stringResource(it.mdbListMessageResource()) },
            tabs = provider.snapshot().tabs,
            capabilities = manager.capabilities,
            onCreate = controller::create,
            onEdit = controller::edit,
            onDelete = controller::requestDelete,
            onNameChange = controller::setName,
            onDescriptionChange = controller::setDescription,
            onPrivacyChange = controller::setPrivacy,
            onSubmit = { scope.launch { controller.submit() } },
            onBack = controller::open,
            onDismiss = controller::dismiss,
        )
    }
}

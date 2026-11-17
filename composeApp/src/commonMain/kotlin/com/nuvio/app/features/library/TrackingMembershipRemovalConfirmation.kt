package com.nuvio.app.features.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.features.tracking.TrackingMembershipApplyResult
import com.nuvio.app.features.tracking.TrackingMembershipRemovalConfirmation
import com.nuvio.app.features.tracking.TrackingMembershipRemovalImpact
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_remove_anyway
import nuvio.composeapp.generated.resources.tracking_remove_confirmation_message
import nuvio.composeapp.generated.resources.tracking_remove_confirmation_title
import nuvio.composeapp.generated.resources.tracking_removal_impact_history
import nuvio.composeapp.generated.resources.tracking_removal_impact_history_and_rating
import nuvio.composeapp.generated.resources.tracking_removal_impact_rating
import org.jetbrains.compose.resources.stringResource

class PendingTrackingMembershipRemoval(
    val itemTitle: String,
    val confirmations: List<TrackingMembershipRemovalConfirmation>,
    val retry: suspend (Set<TrackingProviderId>) -> TrackingMembershipApplyResult,
    val onApplied: suspend (TrackingMembershipApplyResult) -> Unit,
    val onFailure: suspend (Throwable) -> Unit,
)

suspend fun executeTrackingMembershipOperation(
    operation: suspend () -> TrackingMembershipApplyResult,
    onSuccess: suspend (TrackingMembershipApplyResult) -> Unit,
    onFailure: suspend (Throwable) -> Unit,
) {
    try {
        onSuccess(operation())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        onFailure(error)
    }
}

@Composable
fun TrackingMembershipRemovalConfirmationHost(
    pending: PendingTrackingMembershipRemoval?,
    onPendingChange: (PendingTrackingMembershipRemoval?) -> Unit,
) {
    var isBusy by remember(pending) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val confirmations = pending?.confirmations.orEmpty()
    val providerNames = confirmations
        .map { confirmation ->
            TrackingProviderRegistry.authProvider(confirmation.providerId)
                ?.descriptor
                ?.displayName
                ?: confirmation.providerId.storageId.replaceFirstChar(Char::uppercase)
        }
        .distinct()
        .joinToString()
    val impacts = confirmations.flatMapTo(linkedSetOf()) { confirmation -> confirmation.impacts }
    val impactLabel = when (impacts) {
        setOf(TrackingMembershipRemovalImpact.WATCHED_HISTORY) ->
            stringResource(Res.string.tracking_removal_impact_history)

        setOf(TrackingMembershipRemovalImpact.RATING) ->
            stringResource(Res.string.tracking_removal_impact_rating)

        else -> stringResource(Res.string.tracking_removal_impact_history_and_rating)
    }

    NuvioStatusModal(
        title = stringResource(Res.string.tracking_remove_confirmation_title, providerNames),
        message = stringResource(
            Res.string.tracking_remove_confirmation_message,
            pending?.itemTitle.orEmpty(),
            providerNames,
            impactLabel,
        ),
        isVisible = pending != null,
        isBusy = isBusy,
        destructive = true,
        confirmText = stringResource(Res.string.action_remove_anyway),
        dismissText = stringResource(Res.string.action_cancel),
        onConfirm = {
            val request = pending ?: return@NuvioStatusModal
            if (isBusy) return@NuvioStatusModal
            isBusy = true
            scope.launch {
                try {
                    val confirmedProviders = request.confirmations.mapTo(linkedSetOf()) { confirmation ->
                        confirmation.providerId
                    }
                    val result = request.retry(confirmedProviders)
                    if (result.requiresRemovalConfirmation) {
                        onPendingChange(
                            PendingTrackingMembershipRemoval(
                                itemTitle = request.itemTitle,
                                confirmations = result.requiredRemovalConfirmations,
                                retry = request.retry,
                                onApplied = request.onApplied,
                                onFailure = request.onFailure,
                            ),
                        )
                    } else {
                        onPendingChange(null)
                        request.onApplied(result)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    onPendingChange(null)
                    request.onFailure(error)
                } finally {
                    isBusy = false
                }
            }
        },
        onDismiss = {
            if (!isBusy) onPendingChange(null)
        },
    )
}

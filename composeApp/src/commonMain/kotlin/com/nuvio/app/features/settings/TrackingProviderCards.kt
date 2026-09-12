package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.simkl.SimklAuthError
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.simkl.SimklAuthUiState
import com.nuvio.app.features.simkl.SimklBrandAsset
import com.nuvio.app.features.simkl.SimklConnectionMode
import com.nuvio.app.features.simkl.SimklSyncRepository
import com.nuvio.app.features.simkl.simklBrandPainter
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktAuthUiState
import com.nuvio.app.features.trakt.TraktBrandAsset
import com.nuvio.app.features.trakt.TraktConnectionMode
import com.nuvio.app.features.trakt.traktBrandPainter
import com.nuvio.app.features.watchprogress.WatchProgressSourceCoordinator
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.settings_mdblist_disconnect_description
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.settings_simkl_authorization_expired
import nuvio.composeapp.generated.resources.settings_simkl_authorization_revoked
import nuvio.composeapp.generated.resources.settings_simkl_connect
import nuvio.composeapp.generated.resources.settings_simkl_connected_as
import nuvio.composeapp.generated.resources.settings_simkl_connected_description
import nuvio.composeapp.generated.resources.settings_simkl_default_user
import nuvio.composeapp.generated.resources.settings_simkl_disconnect
import nuvio.composeapp.generated.resources.settings_simkl_disconnect_description
import nuvio.composeapp.generated.resources.settings_simkl_finish_sign_in
import nuvio.composeapp.generated.resources.settings_simkl_invalid_callback
import nuvio.composeapp.generated.resources.settings_simkl_missing_credentials
import nuvio.composeapp.generated.resources.settings_simkl_open_login
import nuvio.composeapp.generated.resources.settings_simkl_sign_in_description
import nuvio.composeapp.generated.resources.settings_simkl_sign_in_failed
import nuvio.composeapp.generated.resources.settings_simkl_sync_info_action
import nuvio.composeapp.generated.resources.settings_simkl_sync_now
import nuvio.composeapp.generated.resources.settings_simkl_visit
import nuvio.composeapp.generated.resources.settings_tracking_approval_redirect
import nuvio.composeapp.generated.resources.settings_tracking_disconnect_description
import nuvio.composeapp.generated.resources.settings_tracking_disconnect_title
import nuvio.composeapp.generated.resources.settings_trakt_approval_redirect
import nuvio.composeapp.generated.resources.settings_trakt_connect
import nuvio.composeapp.generated.resources.settings_trakt_connected_as
import nuvio.composeapp.generated.resources.settings_trakt_default_user
import nuvio.composeapp.generated.resources.settings_trakt_disconnect
import nuvio.composeapp.generated.resources.settings_trakt_disconnect_description
import nuvio.composeapp.generated.resources.settings_trakt_failed_open_browser
import nuvio.composeapp.generated.resources.settings_trakt_finish_sign_in
import nuvio.composeapp.generated.resources.settings_trakt_missing_credentials
import nuvio.composeapp.generated.resources.settings_trakt_open_login
import nuvio.composeapp.generated.resources.settings_trakt_save_actions_description
import nuvio.composeapp.generated.resources.settings_trakt_sign_in_description
import org.jetbrains.compose.resources.stringResource

internal enum class TrackingBrand(val displayName: String) {
    NUVIO("Nuvio"),
    TRAKT("Trakt"),
    SIMKL("Simkl"),
    MDBLIST("MDBList"),
    TMDB("TMDB"),
}

internal enum class TrackingConnectionCardMode {
    DISCONNECTED,
    AWAITING_APPROVAL,
    CONNECTED,
}

internal fun isTrackingBrandAvailable(
    brand: TrackingBrand,
    traktConnected: Boolean,
    simklConnected: Boolean,
    mdblistConnected: Boolean = false,
): Boolean = when (brand) {
    TrackingBrand.NUVIO,
    TrackingBrand.TMDB,
    -> true
    TrackingBrand.TRAKT -> traktConnected
    TrackingBrand.SIMKL -> simklConnected
    TrackingBrand.MDBLIST -> mdblistConnected
}

internal fun TraktConnectionMode.toTrackingConnectionCardMode(): TrackingConnectionCardMode = when (this) {
    TraktConnectionMode.DISCONNECTED -> TrackingConnectionCardMode.DISCONNECTED
    TraktConnectionMode.AWAITING_APPROVAL -> TrackingConnectionCardMode.AWAITING_APPROVAL
    TraktConnectionMode.CONNECTED -> TrackingConnectionCardMode.CONNECTED
}

internal fun SimklConnectionMode.toTrackingConnectionCardMode(): TrackingConnectionCardMode = when (this) {
    SimklConnectionMode.DISCONNECTED -> TrackingConnectionCardMode.DISCONNECTED
    SimklConnectionMode.AWAITING_APPROVAL -> TrackingConnectionCardMode.AWAITING_APPROVAL
    SimklConnectionMode.CONNECTED -> TrackingConnectionCardMode.CONNECTED
}

@Composable
internal fun TrackingProviderCards(
    isTablet: Boolean,
    traktUiState: TraktAuthUiState,
    simklUiState: SimklAuthUiState,
) {
    val syncState by remember {
        SimklSyncRepository.ensureLoaded()
        SimklSyncRepository.state
    }.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showSyncInfo by rememberSaveable { mutableStateOf(false) }
    val onSimklSyncRequested: () -> Unit = {
        scope.launch {
            WatchProgressSourceCoordinator.refreshProviderAndActiveSource(
                profileId = ProfileRepository.activeProfileId,
                providerId = TrackingProviderId.SIMKL,
                refreshProvider = {
                    SimklSyncRepository.refresh(TrackingRefreshIntent.USER_INITIATED)
                },
            )
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val useTwoColumns = maxWidth >= 600.dp
        if (useTwoColumns) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TraktProviderCard(
                    uiState = traktUiState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                SimklProviderCard(
                    uiState = simklUiState,
                    isSyncing = syncState.isLoading,
                    syncErrorMessage = syncState.errorMessage,
                    onSyncRequested = onSimklSyncRequested,
                    onInfoRequested = { showSyncInfo = true },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(if (isTablet) 16.dp else 12.dp),
            ) {
                TraktProviderCard(
                    uiState = traktUiState,
                    modifier = Modifier.fillMaxWidth(),
                )
                SimklProviderCard(
                    uiState = simklUiState,
                    isSyncing = syncState.isLoading,
                    syncErrorMessage = syncState.errorMessage,
                    onSyncRequested = onSimklSyncRequested,
                    onInfoRequested = { showSyncInfo = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    Spacer(Modifier.height(if (isTablet) 16.dp else 12.dp))
    MdbListProviderCard(Modifier.fillMaxWidth())

    if (showSyncInfo) {
        SimklSyncInfoDialog(onDismiss = { showSyncInfo = false })
    }
}

@Composable
private fun TraktProviderCard(
    uiState: TraktAuthUiState,
    modifier: Modifier,
) {
    TrackingProviderCard(
        brand = TrackingBrand.TRAKT,
        mode = uiState.mode.toTrackingConnectionCardMode(),
        credentialsConfigured = uiState.credentialsConfigured,
        isLoading = uiState.isLoading,
        connectedLabel = stringResource(
            Res.string.settings_trakt_connected_as,
            uiState.username ?: stringResource(Res.string.settings_trakt_default_user),
        ),
        connectedDescription = stringResource(Res.string.settings_trakt_save_actions_description),
        signInDescription = stringResource(Res.string.settings_trakt_sign_in_description),
        finishSignInLabel = stringResource(Res.string.settings_trakt_finish_sign_in),
        approvalDescription = stringResource(Res.string.settings_trakt_approval_redirect),
        connectLabel = stringResource(Res.string.settings_trakt_connect),
        openLoginLabel = stringResource(Res.string.settings_trakt_open_login),
        disconnectLabel = stringResource(Res.string.settings_trakt_disconnect),
        missingCredentialsMessage = stringResource(Res.string.settings_trakt_missing_credentials),
        statusMessage = uiState.statusMessage.takeUnless {
            uiState.mode == TraktConnectionMode.CONNECTED
        },
        errorMessage = uiState.errorMessage,
        onConnectRequested = TraktAuthRepository::onConnectRequested,
        onResumeAuthorization = {
            TraktAuthRepository.pendingAuthorizationUrl()
                ?: TraktAuthRepository.onConnectRequested()
        },
        onCancelAuthorization = TraktAuthRepository::onCancelAuthorization,
        onDisconnect = TraktAuthRepository::onDisconnectRequested,
        modifier = modifier,
    )
}

@Composable
private fun SimklProviderCard(
    uiState: SimklAuthUiState,
    isSyncing: Boolean,
    syncErrorMessage: String?,
    onSyncRequested: () -> Unit,
    onInfoRequested: () -> Unit,
    modifier: Modifier,
) {
    TrackingProviderCard(
        brand = TrackingBrand.SIMKL,
        mode = uiState.mode.toTrackingConnectionCardMode(),
        credentialsConfigured = uiState.credentialsConfigured,
        isLoading = uiState.isLoading,
        connectedLabel = stringResource(
            Res.string.settings_simkl_connected_as,
            uiState.username ?: stringResource(Res.string.settings_simkl_default_user),
        ),
        connectedDescription = stringResource(Res.string.settings_simkl_connected_description),
        signInDescription = stringResource(Res.string.settings_simkl_sign_in_description),
        finishSignInLabel = stringResource(Res.string.settings_simkl_finish_sign_in),
        approvalDescription = stringResource(Res.string.settings_tracking_approval_redirect),
        connectLabel = stringResource(Res.string.settings_simkl_connect),
        openLoginLabel = stringResource(Res.string.settings_simkl_open_login),
        disconnectLabel = stringResource(Res.string.settings_simkl_disconnect),
        syncLabel = stringResource(Res.string.settings_simkl_sync_now),
        infoLabel = stringResource(Res.string.settings_simkl_sync_info_action),
        isSyncing = isSyncing,
        missingCredentialsMessage = stringResource(Res.string.settings_simkl_missing_credentials),
        errorMessage = simklErrorMessage(uiState.error) ?: syncErrorMessage,
        websiteLabel = stringResource(Res.string.settings_simkl_visit),
        websiteUrl = SIMKL_WEBSITE_URL,
        onConnectRequested = SimklAuthRepository::onConnectRequested,
        onResumeAuthorization = {
            SimklAuthRepository.pendingAuthorizationUrl()
                ?: SimklAuthRepository.onConnectRequested()
        },
        onCancelAuthorization = SimklAuthRepository::onCancelAuthorization,
        onSyncRequested = onSyncRequested,
        onInfoRequested = onInfoRequested,
        onDisconnect = SimklAuthRepository::onDisconnectRequested,
        modifier = modifier,
    )
}

@Composable
internal fun TrackingProviderCard(
    brand: TrackingBrand,
    mode: TrackingConnectionCardMode,
    credentialsConfigured: Boolean,
    isLoading: Boolean,
    connectedLabel: String,
    connectedDescription: String,
    signInDescription: String,
    finishSignInLabel: String,
    approvalDescription: String,
    connectLabel: String,
    openLoginLabel: String,
    disconnectLabel: String,
    missingCredentialsMessage: String,
    modifier: Modifier = Modifier,
    syncLabel: String? = null,
    infoLabel: String? = null,
    isSyncing: Boolean = false,
    authorizationCode: String? = null,
    statusMessage: String? = null,
    errorMessage: String? = null,
    websiteLabel: String? = null,
    websiteUrl: String? = null,
    onConnectRequested: suspend () -> String?,
    onResumeAuthorization: suspend () -> String?,
    onCancelAuthorization: () -> Unit,
    onSyncRequested: (() -> Unit)? = null,
    onInfoRequested: (() -> Unit)? = null,
    onDisconnect: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val uriHandler = LocalUriHandler.current
    val actionScope = rememberCoroutineScope()
    val failedOpenBrowserMessage = stringResource(Res.string.settings_trakt_failed_open_browser)
    var browserError by rememberSaveable { mutableStateOf(false) }
    var showDisconnectDialog by rememberSaveable { mutableStateOf(false) }

    fun openUrl(url: String?) {
        if (url.isNullOrBlank()) return
        browserError = false
        runCatching { uriHandler.openUri(url) }
            .onFailure { browserError = true }
    }

    Box(
        modifier = modifier
            .clip(tokens.shapes.card)
            .background(brand.cardBrush(), tokens.shapes.card)
            .border(
                width = tokens.borders.hairline,
                color = Color.White.copy(alpha = 0.2f),
                shape = tokens.shapes.card,
            ),
    ) {
        TrackingBrandGlyph(
            brand = brand,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .size(150.dp)
                .alpha(0.08f),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (mode == TrackingConnectionCardMode.CONNECTED) 20.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TrackingBrandWordmark(
                brand = brand,
                contentDescription = brand.displayName,
            )

            when (mode) {
                TrackingConnectionCardMode.CONNECTED -> {
                    TrackingConnectedIdentity(
                        label = connectedLabel,
                        description = connectedDescription,
                    )
                    if (syncLabel != null && onSyncRequested != null) {
                        TrackingBrandPrimaryButton(
                            label = syncLabel,
                            loading = isSyncing,
                            enabled = !isLoading && !isSyncing,
                            onClick = onSyncRequested,
                            showSyncIcon = true,
                        )
                    }
                }

                TrackingConnectionCardMode.AWAITING_APPROVAL -> {
                    Text(
                        text = finishSignInLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = approvalDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.78f),
                    )
                    authorizationCode?.let { code ->
                        Text(code, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    }
                    TrackingBrandPrimaryButton(
                        label = openLoginLabel,
                        loading = isLoading,
                        enabled = !isLoading,
                        onClick = { actionScope.launch { openUrl(onResumeAuthorization()) } },
                    )
                    OutlinedButton(
                        onClick = onCancelAuthorization,
                        enabled = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.44f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White,
                            disabledContentColor = Color.White.copy(alpha = 0.45f),
                        ),
                    ) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                }

                TrackingConnectionCardMode.DISCONNECTED -> {
                    Text(
                        text = signInDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                    TrackingBrandPrimaryButton(
                        label = connectLabel,
                        loading = isLoading,
                        enabled = credentialsConfigured && !isLoading,
                        onClick = { actionScope.launch { openUrl(onConnectRequested()) } },
                    )
                    if (!credentialsConfigured) {
                        TrackingBrandMessage(
                            text = missingCredentialsMessage,
                            isError = true,
                        )
                    }
                }
            }

            statusMessage?.takeIf(String::isNotBlank)?.let { message ->
                TrackingBrandMessage(text = message, isError = false)
            }
            errorMessage?.takeIf(String::isNotBlank)?.let { message ->
                TrackingBrandMessage(text = message, isError = true)
            }
            if (browserError) {
                TrackingBrandMessage(text = failedOpenBrowserMessage, isError = true)
            }

            val hasWebsiteAction = !websiteLabel.isNullOrBlank() && !websiteUrl.isNullOrBlank()
            val hasDisconnectAction = mode == TrackingConnectionCardMode.CONNECTED
            val hasInfoAction = mode == TrackingConnectionCardMode.CONNECTED &&
                infoLabel != null && onInfoRequested != null
            val footerActionCount = listOf(
                hasWebsiteAction,
                hasDisconnectAction,
                hasInfoAction,
            ).count { it }
            if (footerActionCount > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (hasWebsiteAction) {
                        TextButton(
                            onClick = { openUrl(websiteUrl) },
                            modifier = if (footerActionCount > 1) Modifier.weight(0.95f) else Modifier,
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        ) {
                            Text(
                                text = websiteLabel.orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (hasDisconnectAction) {
                        TextButton(
                            onClick = { showDisconnectDialog = true },
                            modifier = if (footerActionCount > 1) Modifier.weight(1.05f) else Modifier,
                            enabled = !isLoading && !isSyncing,
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = Color.White.copy(alpha = 0.84f),
                                disabledContentColor = Color.White.copy(alpha = 0.38f),
                            ),
                        ) {
                            Text(
                                text = disconnectLabel,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (hasInfoAction) {
                        TextButton(
                            onClick = { onInfoRequested?.invoke() },
                            modifier = if (footerActionCount > 1) Modifier.weight(1.55f) else Modifier,
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        ) {
                            Text(
                                text = infoLabel.orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDisconnectDialog) {
        TrackingDisconnectDialog(
            brand = brand,
            onConfirm = {
                showDisconnectDialog = false
                onDisconnect()
            },
            onDismiss = { showDisconnectDialog = false },
        )
    }
}

@Composable
private fun TrackingConnectedIdentity(
    label: String,
    description: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.76f),
        )
    }
}

@Composable
private fun TrackingBrandPrimaryButton(
    label: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    showSyncIcon: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color(0xFF171717),
            disabledContainerColor = Color.White.copy(alpha = 0.34f),
            disabledContentColor = Color.White.copy(alpha = 0.7f),
        ),
    ) {
        when {
            loading -> NuvioLoadingIndicator(
                color = Color(0xFF171717),
                modifier = Modifier.size(18.dp),
            )
            showSyncIcon -> Icon(
                imageVector = Icons.Rounded.Sync,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        if (loading || showSyncIcon) {
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(label)
    }
}

@Composable
private fun TrackingBrandMessage(
    text: String,
    isError: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isError) TrackingErrorColor.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.1f),
        shape = RoundedCornerShape(NuvioTokens.Radius.md),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) TrackingErrorColor else Color.White.copy(alpha = 0.82f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackingDisconnectDialog(
    brand: TrackingBrand,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = tokens.components.dialogMaxWidth),
            shape = tokens.shapes.dialog,
            color = tokens.colors.surfaceDialog,
        ) {
            Column(
                modifier = Modifier.padding(tokens.spacing.dialogPadding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_tracking_disconnect_title, brand.displayName),
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when (brand) {
                        TrackingBrand.TRAKT ->
                            stringResource(Res.string.settings_trakt_disconnect_description)
                        TrackingBrand.SIMKL ->
                            stringResource(Res.string.settings_simkl_disconnect_description)
                        TrackingBrand.MDBLIST -> stringResource(Res.string.settings_mdblist_disconnect_description)
                        TrackingBrand.NUVIO,
                        TrackingBrand.TMDB,
                        -> stringResource(
                            Res.string.settings_tracking_disconnect_description,
                            brand.displayName,
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(stringResource(Res.string.settings_trakt_disconnect))
                    }
                }
            }
        }
    }
}

@Composable
internal fun TrackingBrandGlyph(
    brand: TrackingBrand,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    when (brand) {
        TrackingBrand.TRAKT -> Image(
            painter = traktBrandPainter(TraktBrandAsset.Glyph),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
        TrackingBrand.SIMKL -> Image(
            painter = simklBrandPainter(SimklBrandAsset.Glyph),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
        TrackingBrand.MDBLIST -> Image(
            painter = integrationLogoPainter(IntegrationLogo.MdbList),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
        TrackingBrand.TMDB -> Image(
            painter = integrationLogoPainter(IntegrationLogo.Tmdb),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
        TrackingBrand.NUVIO -> Icon(
            imageVector = Icons.Rounded.Sync,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = MaterialTheme.nuvio.colors.accent,
        )
    }
}

@Composable
private fun TrackingBrandWordmark(
    brand: TrackingBrand,
    contentDescription: String,
) {
    val painter: Painter = when (brand) {
        TrackingBrand.TRAKT -> traktBrandPainter(TraktBrandAsset.Wordmark)
        TrackingBrand.SIMKL -> simklBrandPainter(SimklBrandAsset.Wordmark)
        TrackingBrand.MDBLIST -> integrationLogoPainter(IntegrationLogo.MdbList)
        TrackingBrand.NUVIO,
        TrackingBrand.TMDB,
        -> return
    }
    if (brand == TrackingBrand.MDBLIST) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painter, contentDescription = null, modifier = Modifier.size(32.dp))
            Text(contentDescription, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
        }
        return
    }
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = when (brand) {
            TrackingBrand.TRAKT -> Modifier
                .width(86.dp)
                .height(38.dp)
            TrackingBrand.SIMKL -> Modifier
                .width(124.dp)
                .height(30.dp)
            TrackingBrand.MDBLIST -> Modifier.width(130.dp).height(32.dp)
            TrackingBrand.NUVIO,
            TrackingBrand.TMDB,
            -> Modifier
        },
        contentScale = ContentScale.Fit,
        alignment = Alignment.CenterStart,
    )
}

private fun TrackingBrand.cardBrush(): Brush = when (this) {
    TrackingBrand.TRAKT -> Brush.linearGradient(
        colors = listOf(Color(0xFF7D279B), Color(0xFFD61F56), Color(0xFFF22125)),
    )
    TrackingBrand.MDBLIST -> Brush.linearGradient(
        colors = listOf(Color(0xFF173D69), Color(0xFF225C97), Color(0xFF16385D)),
    )
    TrackingBrand.SIMKL -> Brush.linearGradient(
        colors = listOf(Color(0xFF050505), Color(0xFF292929), Color(0xFF111111)),
    )
    TrackingBrand.NUVIO,
    TrackingBrand.TMDB,
    -> Brush.linearGradient(colors = listOf(Color(0xFF242424), Color(0xFF111111)))
}

@Composable
private fun simklErrorMessage(error: SimklAuthError?): String? = when (error) {
    null, SimklAuthError.MISSING_CLIENT_ID -> null
    SimklAuthError.INVALID_CALLBACK,
    SimklAuthError.INVALID_CALLBACK_STATE,
    -> stringResource(Res.string.settings_simkl_invalid_callback)
    SimklAuthError.AUTHORIZATION_EXPIRED ->
        stringResource(Res.string.settings_simkl_authorization_expired)
    SimklAuthError.TOKEN_EXCHANGE_FAILED,
    SimklAuthError.INVALID_TOKEN_RESPONSE,
    -> stringResource(Res.string.settings_simkl_sign_in_failed)
    SimklAuthError.AUTHORIZATION_REVOKED ->
        stringResource(Res.string.settings_simkl_authorization_revoked)
}

private val TrackingErrorColor = Color(0xFFFFDAD6)
private const val SIMKL_WEBSITE_URL = "https://simkl.com"

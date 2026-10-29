package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Button
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.membership.MembershipOverviewRepository
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private enum class CommunityTab {
    Contributors,
    Supporters,
}

private const val PatreonMembershipUrl = "https://www.patreon.com/settings/memberships"

private data class CommunityUiState(
    val selectedTab: CommunityTab = CommunityTab.Contributors,
    val isContributorsLoading: Boolean = false,
    val hasLoadedContributors: Boolean = false,
    val contributors: List<CommunityContributor> = emptyList(),
    val contributorsErrorMessage: String? = null,
    val donationProgress: DonationProgress? = null,
    val isSupportersLoading: Boolean = false,
    val hasLoadedSupporters: Boolean = false,
    val supporters: List<CommunitySupporter> = emptyList(),
    val supportersErrorMessage: String? = null,
)

@Serializable
private data class ContributionsResponseDto(
    val contributors: List<ContributionDto> = emptyList(),
)

@Serializable
private data class ContributionDto(
    val name: String? = null,
    val avatar: String? = null,
    val profile: String? = null,
    val total: Int? = null,
)

@Serializable
private data class DonationsResponseDto(
    val monthlyGoal: DonationMonthlyGoalDto? = null,
)

@Serializable
private data class DonationMonthlyGoalDto(
    val progressPercent: Double? = null,
    val monthLabel: String? = null,
)

@Serializable
private data class SupportersWallResponseDto(
    val top: SupportersWallGroupDto = SupportersWallGroupDto(),
)

@Serializable
private data class SupportersWallGroupDto(
    val members: List<SupporterMemberDto> = emptyList(),
)

@Serializable
private data class SupporterMemberDto(
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val membershipLevel: String? = null,
    val supporterSince: String? = null,
)

internal data class CommunityContributor(
    val login: String,
    val avatarUrl: String?,
    val profileUrl: String?,
    val totalContributions: Int,
)

internal data class CommunitySupporter(
    val key: String,
    val name: String,
    val avatarUrl: String?,
    val membershipLevel: String,
    val supporterSince: String?,
)

internal data class DonationProgress(
    val progressPercent: Int,
)

internal data class SupportersResult(
    val supporters: List<CommunitySupporter>,
    val progress: DonationProgress?,
)

private object SupportersContributorsRepository {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun getContributors(): Result<List<CommunityContributor>> = runCatching {
        val contributionsUrl = CommunityConfig.CONTRIBUTIONS_URL.trim()
        check(contributionsUrl.isNotBlank()) {
            getString(Res.string.community_error_unable_load_contributors)
        }

        val response = httpRequestRaw(
            method = "GET",
            url = contributionsUrl,
            headers = emptyMap(),
            body = "",
        )
        if (response.status !in 200..299) {
            error(getString(Res.string.community_error_contributors_request_failed))
        }

        json.decodeFromString<ContributionsResponseDto>(response.body)
            .contributors
            .mapNotNull(::normalizeContributor)
            .sortedWith(
                compareByDescending<CommunityContributor> { it.totalContributions }
                    .thenBy { it.login.lowercase() },
            )
    }

    suspend fun getSupporters(): Result<SupportersResult> = runCatching {
        val wallUrl = CommunityConfig.SUPPORTERS_WALL_URL.trim()
        check(wallUrl.isNotBlank()) {
            getString(Res.string.community_supporters_not_configured)
        }

        val response = httpRequestRaw(
            method = "GET",
            url = wallUrl,
            headers = emptyMap(),
            body = "",
        )
        if (response.status !in 200..299) {
            error(getString(Res.string.community_error_supporters_request_failed))
        }

        val supporters = json.decodeFromString<SupportersWallResponseDto>(response.body)
            .top
            .members
            .mapNotNull { member ->
                val name = member.displayName?.trim().orEmpty()
                if (name.isBlank()) return@mapNotNull null

                CommunitySupporter(
                    key = "${name.lowercase()}-${member.supporterSince.orEmpty()}",
                    name = name,
                    avatarUrl = member.avatarUrl?.trim()?.takeIf { it.isNotBlank() },
                    membershipLevel = member.membershipLevel?.trim()?.takeIf { it.isNotBlank() }
                        ?: "SUPPORTER",
                    supporterSince = member.supporterSince?.trim()?.takeIf { it.isNotBlank() },
                )
            }
            .mapIndexed { index, supporter ->
                supporter.copy(key = "${supporter.key}#$index")
            }

        SupportersResult(
            supporters = supporters,
            progress = if (AppFeaturePolicy.donationProgressEnabled) getDonationProgress() else null,
        )
    }

    private suspend fun getDonationProgress(): DonationProgress? = runCatching {
        val baseUrl = CommunityConfig.DONATIONS_BASE_URL.trim().removeSuffix("/")
        if (baseUrl.isBlank()) return@runCatching null

        val response = httpRequestRaw(
            method = "GET",
            url = "$baseUrl/api/donations?view=recent",
            headers = emptyMap(),
            body = "",
        )
        if (response.status !in 200..299) return@runCatching null

        json.decodeFromString<DonationsResponseDto>(response.body)
            .monthlyGoal
            ?.progressPercent
            ?.toDonationProgressPercent()
            ?.let { percent -> DonationProgress(progressPercent = percent) }
    }.getOrNull()

    private fun normalizeContributor(dto: ContributionDto): CommunityContributor? {
        val login = dto.name?.trim().orEmpty()
        val contributions = dto.total ?: 0
        if (login.isBlank() || contributions <= 0) return null

        return CommunityContributor(
            login = login,
            avatarUrl = dto.avatar?.trim()?.takeIf { it.isNotBlank() },
            profileUrl = dto.profile?.trim()?.takeIf { it.isNotBlank() },
            totalContributions = contributions,
        )
    }

    private fun Double.toDonationProgressPercent(): Int? {
        if (isNaN()) return null
        return when {
            this >= 100.0 -> 100
            this <= 0.0 -> 0
            else -> roundToInt().coerceIn(0, 99)
        }
    }
}

@Composable
fun SupportersContributorsSettingsScreen(
    onBack: () -> Unit,
) {
    NuvioScreen(
        modifier = Modifier.fillMaxSize(),
    ) {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.compose_settings_page_supporters_contributors),
                onBack = onBack,
            )
        }
        supportersContributorsContent(isTablet = false)
    }
}

internal fun LazyListScope.supportersContributorsContent(
    isTablet: Boolean,
) {
    item {
        SupportersContributorsBody(isTablet = isTablet)
    }
}

@Composable
private fun SupportersContributorsBody(
    isTablet: Boolean,
) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val donateUrl = remember { CommunityConfig.DONATIONS_DONATE_URL.trim().removeSuffix("/") }
    val donationsConfigured = remember { CommunityConfig.DONATIONS_BASE_URL.trim().isNotBlank() }
    val donateConfigured = donateUrl.isNotBlank()
    val contributorsErrorFallback = stringResource(Res.string.community_error_unable_load_contributors)
    val supportersErrorFallback = stringResource(Res.string.community_error_unable_load_supporters)
    val membershipState by remember {
        MembershipOverviewRepository.ensureStarted()
        MembershipOverviewRepository.state
    }.collectAsStateWithLifecycle()

    var uiState by remember { mutableStateOf(CommunityUiState()) }
    var selectedContributor by remember { mutableStateOf<CommunityContributor?>(null) }
    var selectedSupporter by remember { mutableStateOf<CommunitySupporter?>(null) }

    fun loadContributors(force: Boolean) {
        if (uiState.isContributorsLoading) return
        if (!force && uiState.hasLoadedContributors) return
        scope.launch {
            uiState = uiState.copy(
                isContributorsLoading = true,
                contributorsErrorMessage = null,
            )
            SupportersContributorsRepository.getContributors()
                .onSuccess { contributors ->
                    uiState = uiState.copy(
                        isContributorsLoading = false,
                        hasLoadedContributors = true,
                        contributors = contributors,
                        contributorsErrorMessage = null,
                    )
                }
                .onFailure { error ->
                    uiState = uiState.copy(
                        isContributorsLoading = false,
                        hasLoadedContributors = false,
                        contributors = emptyList(),
                        contributorsErrorMessage = error.message ?: contributorsErrorFallback,
                    )
                }
        }
    }

    fun loadSupporters(force: Boolean) {
        if (uiState.isSupportersLoading) return
        if (!force && uiState.hasLoadedSupporters) return
        scope.launch {
            uiState = uiState.copy(
                isSupportersLoading = true,
                supportersErrorMessage = null,
            )
            SupportersContributorsRepository.getSupporters()
                .onSuccess { result ->
                    uiState = uiState.copy(
                        isSupportersLoading = false,
                        hasLoadedSupporters = true,
                        supporters = result.supporters,
                        donationProgress = result.progress,
                        supportersErrorMessage = null,
                    )
                }
                .onFailure { error ->
                    uiState = uiState.copy(
                        isSupportersLoading = false,
                        hasLoadedSupporters = false,
                        supporters = emptyList(),
                        donationProgress = null,
                        supportersErrorMessage = error.message ?: supportersErrorFallback,
                    )
                }
        }
    }

    LaunchedEffect(Unit) {
        loadContributors(force = false)
        loadSupporters(force = false)
    }

    LaunchedEffect(uiState.selectedTab) {
        if (uiState.selectedTab == CommunityTab.Supporters) {
            loadSupporters(force = false)
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(if (isTablet) 18.dp else 14.dp),
    ) {
        SupporterMembershipCard(
            state = membershipState,
            isTablet = isTablet,
            showAction = AppFeaturePolicy.donationActionsEnabled,
            actionEnabled = membershipState.overview?.subscriptionActive == true || donateConfigured,
            onAction = {
                val url = if (membershipState.overview?.subscriptionActive == true) {
                    PatreonMembershipUrl
                } else {
                    donateUrl
                }
                if (url.isNotBlank()) uriHandler.openUri(url)
            },
            onRefresh = MembershipOverviewRepository::refresh,
        )

        if (AppFeaturePolicy.donationProgressEnabled) {
            NuvioSurfaceCard {
                if (donationsConfigured) {
                    DonationProgressSection(
                        progress = uiState.donationProgress,
                        isLoading = uiState.isSupportersLoading,
                        errorMessage = uiState.supportersErrorMessage,
                    )
                } else {
                    Text(
                        text = stringResource(Res.string.community_supporters_not_configured),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        NuvioSurfaceCard {
            CommunityTabRow(
                selectedTab = uiState.selectedTab,
                onSelectTab = { tab -> uiState = uiState.copy(selectedTab = tab) },
            )
        }

        when (uiState.selectedTab) {
            CommunityTab.Contributors -> ContributorsCard(
                contributors = uiState.contributors,
                isLoading = uiState.isContributorsLoading,
                errorMessage = uiState.contributorsErrorMessage,
                onRetry = { loadContributors(force = true) },
                onContributorClick = { selectedContributor = it },
            )

            CommunityTab.Supporters -> SupportersCard(
                supporters = uiState.supporters,
                isLoading = uiState.isSupportersLoading,
                errorMessage = uiState.supportersErrorMessage,
                onRetry = { loadSupporters(force = true) },
                onSupporterClick = { selectedSupporter = it },
            )
        }
    }

    selectedContributor?.let { contributor ->
        val supportUrl = contributorSupportLink(contributor.login)
        CommunityDetailsDialog(
            title = contributor.login,
            subtitle = null,
            onDismiss = { selectedContributor = null },
            primaryActionLabel = if (contributor.profileUrl != null) {
                stringResource(Res.string.community_open_github)
            } else {
                null
            },
            onPrimaryAction = contributor.profileUrl?.let { url -> { uriHandler.openUri(url) } },
            secondaryActionLabel = if (AppFeaturePolicy.donationActionsEnabled && supportUrl != null) {
                stringResource(Res.string.action_donate)
            } else {
                null
            },
            onSecondaryAction = if (AppFeaturePolicy.donationActionsEnabled) {
                supportUrl?.let { url -> { uriHandler.openUri(url) } }
            } else {
                null
            },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CommunityAvatar(
                    label = contributor.login,
                    imageUrl = contributor.avatarUrl,
                    modifier = Modifier.size(72.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = contributor.profileUrl ?: stringResource(Res.string.community_github_profile_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    selectedSupporter?.let { supporter ->
        CommunityDetailsDialog(
            title = supporter.name,
            subtitle = supporter.supporterSince?.let { formatDonationDate(it) },
            onDismiss = { selectedSupporter = null },
            primaryActionLabel = null,
            onPrimaryAction = null,
            secondaryActionLabel = null,
            onSecondaryAction = null,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CommunityAvatar(
                    label = supporter.name,
                    imageUrl = supporter.avatarUrl,
                    modifier = Modifier.size(72.dp),
                )
                Text(
                    text = formatMembershipLevel(supporter.membershipLevel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CommunityTabRow(
    selectedTab: CommunityTab,
    onSelectTab: (CommunityTab) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CommunityTab.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { onSelectTab(tab) },
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                },
                shape = RoundedCornerShape(999.dp),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (tab == CommunityTab.Contributors) {
                            stringResource(Res.string.community_tab_contributors)
                        } else {
                            stringResource(Res.string.community_tab_supporters)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContributorsCard(
    contributors: List<CommunityContributor>,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onContributorClick: (CommunityContributor) -> Unit,
) {
    NuvioSurfaceCard {
        when {
            isLoading -> LoadingState(label = stringResource(Res.string.community_loading_contributors))
            errorMessage != null -> ErrorState(
                title = stringResource(Res.string.community_load_contributors_failed),
                message = errorMessage,
                onRetry = onRetry,
            )
            contributors.isEmpty() -> EmptyState(label = stringResource(Res.string.community_empty_contributors))
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(
                    items = contributors,
                    key = { contributor -> "${contributor.login.lowercase()}-${contributor.profileUrl.orEmpty()}" },
                ) { contributor ->
                    ContributorRow(
                        contributor = contributor,
                        onClick = { onContributorClick(contributor) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SupportersCard(
    supporters: List<CommunitySupporter>,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onSupporterClick: (CommunitySupporter) -> Unit,
) {
    NuvioSurfaceCard {
        when {
            isLoading -> LoadingState(label = stringResource(Res.string.community_loading_supporters))
            errorMessage != null -> ErrorState(
                title = stringResource(Res.string.community_load_supporters_failed),
                message = errorMessage,
                onRetry = onRetry,
            )
            supporters.isEmpty() -> EmptyState(label = stringResource(Res.string.community_empty_supporters))
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(
                    items = supporters,
                    key = { supporter -> supporter.key },
                ) { supporter ->
                    SupporterRow(
                        supporter = supporter,
                        onClick = { onSupporterClick(supporter) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DonationProgressSection(
    progress: DonationProgress?,
    isLoading: Boolean,
    errorMessage: String?,
) {
    val percent = progress?.progressPercent ?: 0
    val progressFraction = (percent / 100f).coerceIn(0f, 1f)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(Res.string.community_donation_progress_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (progress != null && errorMessage == null) {
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (isLoading && progress == null) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp)),
            )
        } else {
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp)),
            )
        }

        Text(
            text = when {
                errorMessage != null -> errorMessage
                isLoading && progress == null -> stringResource(Res.string.community_loading_donation_progress)
                percent >= 100 -> stringResource(Res.string.community_donation_progress_complete)
                else -> stringResource(Res.string.community_donation_progress_remaining)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (errorMessage != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun ContributorRow(
    contributor: CommunityContributor,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CommunityAvatar(
            label = contributor.login,
            imageUrl = contributor.avatarUrl,
            modifier = Modifier.size(54.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = contributor.login,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SupporterRow(
    supporter: CommunitySupporter,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CommunityAvatar(
            label = supporter.name,
            imageUrl = supporter.avatarUrl,
            modifier = Modifier.size(54.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = supporter.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatMembershipLevel(supporter.membershipLevel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            supporter.supporterSince?.let { supporterSince ->
                Text(
                    text = formatDonationDate(supporterSince),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CommunityAvatar(
    label: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl.isNullOrBlank()) {
            Text(
                text = label.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            AsyncImage(
                model = imageUrl,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun LoadingState(
    label: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NuvioLoadingIndicator()
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState(
    label: String,
) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ErrorState(
    title: String,
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry) {
            Text(stringResource(Res.string.action_retry))
        }
    }
}

@Composable
private fun CommunityDetailsDialog(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    primaryActionLabel: String?,
    onPrimaryAction: (() -> Unit)?,
    secondaryActionLabel: String?,
    onSecondaryAction: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = title,
        message = subtitle?.takeIf(String::isNotBlank),
    ) {
        content()

        val hasPrimary = primaryActionLabel != null && onPrimaryAction != null
        val hasSecondary = secondaryActionLabel != null && onSecondaryAction != null
        if (hasPrimary || hasSecondary) {
            DialogButtons {
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    DialogButton(
                        text = secondaryActionLabel,
                        onClick = onSecondaryAction,
                    )
                }
                if (primaryActionLabel != null && onPrimaryAction != null) {
                    DialogButton(
                        text = primaryActionLabel,
                        onClick = onPrimaryAction,
                        style = DialogButtonStyle.Primary,
                    )
                }
            }
        }
    }
}

private fun contributorSupportLink(login: String): String? = when (login.lowercase()) {
    "skoruppa" -> "https://ko-fi.com/skoruppa"
    "whitegiso" -> "https://ko-fi.com/whitegiso"
    "edoedac0" -> "https://ko-fi.com/edoedac"
    "crisszollo", "xrissozollo" -> "https://ko-fi.com/crisszollo"
    else -> null
}

private fun formatMembershipLevel(rawLevel: String): String = rawLevel
    .split('_')
    .filter(String::isNotBlank)
    .joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.titlecase() } }

@Composable
internal fun formatDonationDate(rawDate: String): String {
    val datePart = rawDate.substringBefore('T')
    val parts = datePart.split('-')
    if (parts.size != 3) return rawDate
    val year = parts[0]
    val month = parts[1].toIntOrNull()?.let { monthIndex ->
        listOf(
            stringResource(Res.string.community_month_jan),
            stringResource(Res.string.community_month_feb),
            stringResource(Res.string.community_month_mar),
            stringResource(Res.string.community_month_apr),
            stringResource(Res.string.community_month_may),
            stringResource(Res.string.community_month_jun),
            stringResource(Res.string.community_month_jul),
            stringResource(Res.string.community_month_aug),
            stringResource(Res.string.community_month_sep),
            stringResource(Res.string.community_month_oct),
            stringResource(Res.string.community_month_nov),
            stringResource(Res.string.community_month_dec),
        ).getOrNull(monthIndex - 1)
    } ?: return rawDate
    val day = parts[2].toIntOrNull()?.toString() ?: return rawDate
    return stringResource(Res.string.community_date_format, month, day, year)
}

package com.nuvio.app.features.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.nuvio.app.core.ui.CardDepthStyleRepository
import com.nuvio.app.core.ui.CardDepthStyleUiState
import com.nuvio.app.core.poster.CustomPosterUrlRepository
import com.nuvio.app.core.ui.DefaultCardDepthEdgeCoverage
import com.nuvio.app.core.ui.DefaultCardDepthEdgeStrength
import com.nuvio.app.core.ui.DefaultCardDepthSheenStrength
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioCardDepthSurface
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.core.ui.PosterCardStyleUiState
import com.nuvio.app.core.ui.cardDepthVisual
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_reset
import nuvio.composeapp.generated.resources.settings_card_depth_apply_to
import nuvio.composeapp.generated.resources.settings_card_depth_description
import nuvio.composeapp.generated.resources.settings_card_depth_edge
import nuvio.composeapp.generated.resources.settings_card_depth_edge_balanced
import nuvio.composeapp.generated.resources.settings_card_depth_edge_bold
import nuvio.composeapp.generated.resources.settings_card_depth_edge_subtle
import nuvio.composeapp.generated.resources.settings_card_depth_edge_value
import nuvio.composeapp.generated.resources.settings_card_depth_edge_coverage
import nuvio.composeapp.generated.resources.settings_card_depth_coverage_full
import nuvio.composeapp.generated.resources.settings_card_depth_coverage_half
import nuvio.composeapp.generated.resources.settings_card_depth_coverage_top
import nuvio.composeapp.generated.resources.settings_card_depth_coverage_value
import nuvio.composeapp.generated.resources.settings_card_depth_enabled
import nuvio.composeapp.generated.resources.settings_card_depth_fine_tune
import nuvio.composeapp.generated.resources.settings_card_depth_fine_tune_hint
import nuvio.composeapp.generated.resources.settings_card_depth_fine_tune_title
import nuvio.composeapp.generated.resources.settings_card_depth_pad_edge_axis
import nuvio.composeapp.generated.resources.settings_card_depth_pad_sheen_axis
import nuvio.composeapp.generated.resources.settings_card_depth_preview_meta
import nuvio.composeapp.generated.resources.settings_card_depth_preview_title
import nuvio.composeapp.generated.resources.settings_card_depth_sheen
import nuvio.composeapp.generated.resources.settings_card_depth_sheen_value
import nuvio.composeapp.generated.resources.settings_card_depth_sheen_bright
import nuvio.composeapp.generated.resources.settings_card_depth_sheen_off
import nuvio.composeapp.generated.resources.settings_card_depth_sheen_soft
import nuvio.composeapp.generated.resources.settings_card_depth_surface_cast
import nuvio.composeapp.generated.resources.settings_card_depth_surface_continue_watching
import nuvio.composeapp.generated.resources.settings_card_depth_surface_episodes
import nuvio.composeapp.generated.resources.settings_card_depth_surface_posters
import nuvio.composeapp.generated.resources.settings_card_depth_surface_trailers
import nuvio.composeapp.generated.resources.settings_card_depth_title
import nuvio.composeapp.generated.resources.settings_poster_card_radius
import nuvio.composeapp.generated.resources.settings_poster_card_style
import nuvio.composeapp.generated.resources.settings_poster_card_width
import nuvio.composeapp.generated.resources.settings_poster_custom
import nuvio.composeapp.generated.resources.settings_poster_description
import nuvio.composeapp.generated.resources.settings_poster_hide_labels
import nuvio.composeapp.generated.resources.settings_poster_landscape_mode
import nuvio.composeapp.generated.resources.settings_poster_live_preview
import nuvio.composeapp.generated.resources.settings_poster_option_with_value
import nuvio.composeapp.generated.resources.settings_poster_preview_corner_radius
import nuvio.composeapp.generated.resources.settings_poster_preview_height
import nuvio.composeapp.generated.resources.settings_poster_preview_width
import nuvio.composeapp.generated.resources.settings_poster_radius_classic
import nuvio.composeapp.generated.resources.settings_poster_radius_pill
import nuvio.composeapp.generated.resources.settings_poster_radius_rounded
import nuvio.composeapp.generated.resources.settings_poster_radius_sharp
import nuvio.composeapp.generated.resources.settings_poster_radius_subtle
import nuvio.composeapp.generated.resources.settings_poster_width_balanced
import nuvio.composeapp.generated.resources.settings_poster_width_comfort
import nuvio.composeapp.generated.resources.settings_poster_width_compact
import nuvio.composeapp.generated.resources.settings_poster_width_dense
import nuvio.composeapp.generated.resources.settings_poster_width_large
import nuvio.composeapp.generated.resources.settings_poster_width_standard
import nuvio.composeapp.generated.resources.settings_custom_poster_title
import nuvio.composeapp.generated.resources.settings_custom_poster_description
import nuvio.composeapp.generated.resources.settings_custom_poster_placeholder
import nuvio.composeapp.generated.resources.settings_custom_poster_active
import nuvio.composeapp.generated.resources.settings_custom_poster_save
import nuvio.composeapp.generated.resources.settings_custom_poster_clear
import nuvio.composeapp.generated.resources.settings_custom_poster_apply_to
import nuvio.composeapp.generated.resources.settings_custom_poster_screen_home
import nuvio.composeapp.generated.resources.settings_custom_poster_screen_continue_watching
import nuvio.composeapp.generated.resources.settings_custom_poster_screen_collections
import nuvio.composeapp.generated.resources.settings_custom_poster_screen_library
import nuvio.composeapp.generated.resources.settings_custom_poster_screen_search
import nuvio.composeapp.generated.resources.settings_custom_poster_screen_details
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.posterCustomizationSettingsContent(
    isTablet: Boolean,
    uiState: PosterCardStyleUiState,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_poster_card_style),
            isTablet = isTablet,
            actions = {
                NuvioActionLabel(
                    text = stringResource(Res.string.action_reset),
                    onClick = PosterCardStyleRepository::resetToDefaults,
                )
            },
        ) {
            SettingsGroup(isTablet = isTablet) {
                PosterCardStyleControls(
                    isTablet = isTablet,
                    widthDp = uiState.widthDp,
                    cornerRadiusDp = uiState.cornerRadiusDp,
                    catalogLandscapeModeEnabled = uiState.catalogLandscapeModeEnabled,
                    hideLabelsEnabled = uiState.hideLabelsEnabled,
                    onWidthSelected = PosterCardStyleRepository::setWidthDp,
                    onCornerRadiusSelected = PosterCardStyleRepository::setCornerRadiusDp,
                    onCatalogLandscapeModeChange = PosterCardStyleRepository::setCatalogLandscapeModeEnabled,
                    onHideLabelsChange = PosterCardStyleRepository::setHideLabelsEnabled,
                )
            }
        }
    }
    item {
        CustomPosterUrlRepository.ensureLoaded()
        val currentPattern by CustomPosterUrlRepository.pattern.collectAsState()
        var editingPattern by rememberSaveable(currentPattern) { mutableStateOf(currentPattern) }
        val isActive = currentPattern.isNotBlank()

        SettingsSection(
            title = stringResource(Res.string.settings_custom_poster_title),
            isTablet = isTablet,
            actions = {
                if (isActive) {
                    NuvioActionLabel(
                        text = stringResource(Res.string.settings_custom_poster_clear),
                        onClick = {
                            editingPattern = ""
                            CustomPosterUrlRepository.clearPattern()
                        },
                    )
                }
            },
        ) {
            SettingsGroup(isTablet = isTablet) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.settings_custom_poster_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = editingPattern,
                        onValueChange = { editingPattern = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                text = stringResource(Res.string.settings_custom_poster_placeholder),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        singleLine = false,
                        maxLines = 4,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                CustomPosterUrlRepository.setPattern(editingPattern)
                            },
                            enabled = editingPattern.trim() != currentPattern,
                        ) {
                            Text(text = stringResource(Res.string.settings_custom_poster_save))
                        }
                    }
                    if (isActive) {
                        Text(
                            text = "✓ ${stringResource(Res.string.settings_custom_poster_active)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(Res.string.settings_custom_poster_apply_to),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val enabledScreens by CustomPosterUrlRepository.enabledScreens.collectAsState()
                        val screenEntries = listOf(
                            com.nuvio.app.core.poster.CustomPosterScreen.HOME to stringResource(Res.string.settings_custom_poster_screen_home),
                            com.nuvio.app.core.poster.CustomPosterScreen.CONTINUE_WATCHING to stringResource(Res.string.settings_custom_poster_screen_continue_watching),
                            com.nuvio.app.core.poster.CustomPosterScreen.COLLECTIONS to stringResource(Res.string.settings_custom_poster_screen_collections),
                            com.nuvio.app.core.poster.CustomPosterScreen.LIBRARY to stringResource(Res.string.settings_custom_poster_screen_library),
                            com.nuvio.app.core.poster.CustomPosterScreen.SEARCH to stringResource(Res.string.settings_custom_poster_screen_search),
                            com.nuvio.app.core.poster.CustomPosterScreen.DETAILS to stringResource(Res.string.settings_custom_poster_screen_details),
                        )
                        screenEntries.forEach { (screen, label) ->
                            PosterToggleRow(
                                title = label,
                                checked = screen in enabledScreens,
                                onCheckedChange = { enabled ->
                                    CustomPosterUrlRepository.setScreenEnabled(screen, enabled)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    item {
        CardDepthStyleRepository.ensureLoaded()
        val cardDepthState by CardDepthStyleRepository.uiState.collectAsState()
        SettingsSection(
            title = stringResource(Res.string.settings_card_depth_title),
            isTablet = isTablet,
            actions = {
                NuvioActionLabel(
                    text = stringResource(Res.string.action_reset),
                    onClick = CardDepthStyleRepository::resetToDefaults,
                )
            },
        ) {
            SettingsGroup(isTablet = isTablet) {
                CardDepthStyleControls(
                    isTablet = isTablet,
                    uiState = cardDepthState,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardDepthStyleControls(
    isTablet: Boolean,
    uiState: CardDepthStyleUiState,
) {
    var showFineTune by remember { mutableStateOf(false) }
    val edgeOptions = listOf(
        PresetOption(stringResource(Res.string.settings_card_depth_edge_subtle), 28),
        PresetOption(stringResource(Res.string.settings_card_depth_edge_balanced), 42),
        PresetOption(stringResource(Res.string.settings_card_depth_edge_bold), 56),
    )
    val sheenOptions = listOf(
        PresetOption(stringResource(Res.string.settings_card_depth_sheen_off), 0),
        PresetOption(stringResource(Res.string.settings_card_depth_sheen_soft), 10),
        PresetOption(stringResource(Res.string.settings_card_depth_sheen_bright), 16),
    )
    val coverageOptions = listOf(
        PresetOption(stringResource(Res.string.settings_card_depth_coverage_top), 0),
        PresetOption(stringResource(Res.string.settings_card_depth_coverage_half), 50),
        PresetOption(stringResource(Res.string.settings_card_depth_coverage_full), 100),
    )
    val surfaceRows = listOf(
        stringResource(Res.string.settings_card_depth_surface_posters) to NuvioCardDepthSurface.Posters,
        stringResource(Res.string.settings_card_depth_surface_continue_watching) to NuvioCardDepthSurface.ContinueWatching,
        stringResource(Res.string.settings_card_depth_surface_episodes) to NuvioCardDepthSurface.EpisodeCards,
        stringResource(Res.string.settings_card_depth_surface_cast) to NuvioCardDepthSurface.Cast,
        stringResource(Res.string.settings_card_depth_surface_trailers) to NuvioCardDepthSurface.Trailers,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = if (isTablet) 18.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_card_depth_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PosterToggleRow(
            title = stringResource(Res.string.settings_card_depth_enabled),
            checked = uiState.enabled,
            onCheckedChange = CardDepthStyleRepository::setEnabled,
        )
        if (uiState.enabled) {
            PosterStyleOptionRow(
                title = stringResource(Res.string.settings_card_depth_edge),
                selectedValue = uiState.edgeStrength,
                options = edgeOptions,
                onSelected = CardDepthStyleRepository::setEdgeStrength,
            )
            PosterStyleOptionRow(
                title = stringResource(Res.string.settings_card_depth_sheen),
                selectedValue = uiState.sheenStrength,
                options = sheenOptions,
                onSelected = CardDepthStyleRepository::setSheenStrength,
            )
            PosterStyleOptionRow(
                title = stringResource(Res.string.settings_card_depth_edge_coverage),
                selectedValue = uiState.edgeCoverage,
                options = coverageOptions,
                onSelected = CardDepthStyleRepository::setEdgeCoverage,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showFineTune = true },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.settings_card_depth_fine_tune),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = stringResource(Res.string.settings_card_depth_apply_to),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            surfaceRows.forEach { (title, surface) ->
                PosterToggleRow(
                    title = title,
                    checked = uiState.isSurfaceEnabled(surface),
                    onCheckedChange = { enabled ->
                        CardDepthStyleRepository.setSurfaceEnabled(surface, enabled)
                    },
                )
            }
        }
    }

    if (showFineTune) {
        CardDepthFineTuneSheet(
            initialEdgeStrength = uiState.edgeStrength,
            initialSheenStrength = uiState.sheenStrength,
            initialEdgeCoverage = uiState.edgeCoverage,
            onDismiss = { showFineTune = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardDepthFineTuneSheet(
    initialEdgeStrength: Int,
    initialSheenStrength: Int,
    initialEdgeCoverage: Int,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draftEdge by remember { mutableFloatStateOf(initialEdgeStrength.toFloat()) }
    var draftSheen by remember { mutableFloatStateOf(initialSheenStrength.toFloat()) }
    var draftCoverage by remember { mutableFloatStateOf(initialEdgeCoverage.toFloat()) }

    fun commitDraft() {
        CardDepthStyleRepository.setEdgeStrength(draftEdge.roundToInt())
        CardDepthStyleRepository.setSheenStrength(draftSheen.roundToInt())
        CardDepthStyleRepository.setEdgeCoverage(draftCoverage.roundToInt())
    }

    NuvioModalBottomSheet(
        onDismissRequest = {
            commitDraft()
            onDismiss()
        },
        sheetState = sheetState,
        fullHeight = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.settings_card_depth_fine_tune_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                NuvioActionLabel(
                    text = stringResource(Res.string.action_reset),
                    onClick = {
                        draftEdge = DefaultCardDepthEdgeStrength.toFloat()
                        draftSheen = DefaultCardDepthSheenStrength.toFloat()
                        draftCoverage = DefaultCardDepthEdgeCoverage.toFloat()
                        commitDraft()
                    },
                )
            }
            Text(
                text = stringResource(Res.string.settings_card_depth_fine_tune_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardDepthPreviewCard(
                edgeStrength = draftEdge,
                sheenStrength = draftSheen,
                edgeCoverage = draftCoverage,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${stringResource(Res.string.settings_card_depth_edge_value)}: ${formatDepthPercentage(draftEdge)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${stringResource(Res.string.settings_card_depth_sheen_value)}: ${formatDepthPercentage(draftSheen)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            CardDepthTuningPad(
                edgeStrength = draftEdge,
                sheenStrength = draftSheen,
                onChange = { edge, sheen ->
                    draftEdge = edge
                    draftSheen = sheen
                },
                onCommit = { edge, sheen ->
                    CardDepthStyleRepository.setEdgeStrength(edge.roundToInt())
                    CardDepthStyleRepository.setSheenStrength(sheen.roundToInt())
                },
            )
            Text(
                text = "${stringResource(Res.string.settings_card_depth_coverage_value)}: ${formatDepthPercentage(draftCoverage)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Slider(
                value = draftCoverage,
                onValueChange = { draftCoverage = it },
                onValueChangeFinished = {
                    CardDepthStyleRepository.setEdgeCoverage(draftCoverage.roundToInt())
                },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun formatDepthPercentage(value: Float): String {
    val tenths = (value.coerceIn(0f, 100f) * 10f).roundToInt()
    return "${tenths / 10}.${tenths % 10}%"
}

@Composable
private fun CardDepthPreviewCard(
    edgeStrength: Float,
    sheenStrength: Float,
    edgeCoverage: Float,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF33415C),
                        Color(0xFF232D42),
                        Color(0xFF141A28),
                    ),
                ),
            )
            .cardDepthVisual(
                shape = shape,
                edgeStrength = edgeStrength,
                sheenStrength = sheenStrength,
                edgeCoverage = edgeCoverage,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.42f to Color.Transparent,
                        0.56f to Color.Black.copy(alpha = 0.20f),
                        0.70f to Color.Black.copy(alpha = 0.45f),
                        0.84f to Color.Black.copy(alpha = 0.68f),
                        1f to Color.Black.copy(alpha = 0.92f),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(Res.string.settings_card_depth_preview_meta),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.78f),
            )
            Text(
                text = stringResource(Res.string.settings_card_depth_preview_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}

@Composable
private fun CardDepthTuningPad(
    edgeStrength: Float,
    sheenStrength: Float,
    onChange: (Float, Float) -> Unit,
    onCommit: (Float, Float) -> Unit,
) {
    val maxEdge = 70f
    val maxSheen = 25f
    val shape = RoundedCornerShape(16.dp)
    val thumbColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val currentEdge by rememberUpdatedState(edgeStrength)
    val currentSheen by rememberUpdatedState(sheenStrength)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnCommit by rememberUpdatedState(onCommit)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = shape,
            )
            .pointerInput(Unit) {
                fun valuesAt(position: Offset): Pair<Float, Float> {
                    val x = (position.x / size.width).coerceIn(0f, 1f)
                    val y = 1f - (position.y / size.height).coerceIn(0f, 1f)
                    return (x * maxEdge) to (y * maxSheen)
                }
                detectDragGestures(
                    onDragStart = { position ->
                        val (edge, sheen) = valuesAt(position)
                        currentOnChange(edge, sheen)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val (edge, sheen) = valuesAt(change.position)
                        currentOnChange(edge, sheen)
                    },
                    onDragEnd = {
                        currentOnCommit(currentEdge, currentSheen)
                    },
                    onDragCancel = {
                        currentOnCommit(currentEdge, currentSheen)
                    },
                )
            }
            .pointerInput(Unit) {
                fun valuesAt(position: Offset): Pair<Float, Float> {
                    val x = (position.x / size.width).coerceIn(0f, 1f)
                    val y = 1f - (position.y / size.height).coerceIn(0f, 1f)
                    return (x * maxEdge) to (y * maxSheen)
                }
                detectTapGestures { position ->
                    val (edge, sheen) = valuesAt(position)
                    currentOnChange(edge, sheen)
                    currentOnCommit(edge, sheen)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            for (step in 1..3) {
                val x = size.width * step / 4f
                val y = size.height * step / 4f
                drawLine(
                    color = gridColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            val thumbX = size.width * (edgeStrength / maxEdge).coerceIn(0f, 1f)
            val thumbY = size.height * (1f - (sheenStrength / maxSheen).coerceIn(0f, 1f))
            drawLine(
                color = thumbColor.copy(alpha = 0.35f),
                start = Offset(thumbX, 0f),
                end = Offset(thumbX, size.height),
                strokeWidth = 1.dp.toPx(),
            )
            drawLine(
                color = thumbColor.copy(alpha = 0.35f),
                start = Offset(0f, thumbY),
                end = Offset(size.width, thumbY),
                strokeWidth = 1.dp.toPx(),
            )
            drawCircle(
                color = thumbColor.copy(alpha = 0.25f),
                radius = 16.dp.toPx(),
                center = Offset(thumbX, thumbY),
            )
            drawCircle(
                color = thumbColor,
                radius = 9.dp.toPx(),
                center = Offset(thumbX, thumbY),
            )
            drawCircle(
                color = Color.White,
                radius = 4.dp.toPx(),
                center = Offset(thumbX, thumbY),
            )
        }
        Text(
            text = stringResource(Res.string.settings_card_depth_pad_sheen_axis),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp),
        )
        Text(
            text = stringResource(Res.string.settings_card_depth_pad_edge_axis),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PosterCardStyleControls(
    isTablet: Boolean,
    widthDp: Int,
    cornerRadiusDp: Int,
    catalogLandscapeModeEnabled: Boolean,
    hideLabelsEnabled: Boolean,
    onWidthSelected: (Int) -> Unit,
    onCornerRadiusSelected: (Int) -> Unit,
    onCatalogLandscapeModeChange: (Boolean) -> Unit,
    onHideLabelsChange: (Boolean) -> Unit,
) {
    val widthOptions = listOf(
        PresetOption(stringResource(Res.string.settings_poster_width_compact), 104),
        PresetOption(stringResource(Res.string.settings_poster_width_dense), 112),
        PresetOption(stringResource(Res.string.settings_poster_width_standard), 120),
        PresetOption(stringResource(Res.string.settings_poster_width_balanced), 126),
        PresetOption(stringResource(Res.string.settings_poster_width_comfort), 134),
        PresetOption(stringResource(Res.string.settings_poster_width_large), 140),
    )
    val radiusOptions = listOf(
        PresetOption(stringResource(Res.string.settings_poster_radius_sharp), 0),
        PresetOption(stringResource(Res.string.settings_poster_radius_subtle), 4),
        PresetOption(stringResource(Res.string.settings_poster_radius_classic), 8),
        PresetOption(stringResource(Res.string.settings_poster_radius_rounded), 12),
        PresetOption(stringResource(Res.string.settings_poster_radius_pill), 16),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = if (isTablet) 18.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_poster_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PosterCardLivePreview(
            widthDp = widthDp,
            cornerRadiusDp = cornerRadiusDp,
        )
        PosterStyleOptionRow(
            title = stringResource(Res.string.settings_poster_card_width),
            selectedValue = widthDp,
            options = widthOptions,
            onSelected = onWidthSelected,
        )
        PosterStyleOptionRow(
            title = stringResource(Res.string.settings_poster_card_radius),
            selectedValue = cornerRadiusDp,
            options = radiusOptions,
            onSelected = onCornerRadiusSelected,
        )
        PosterLandscapeModeToggleRow(
            checked = catalogLandscapeModeEnabled,
            onCheckedChange = onCatalogLandscapeModeChange,
        )
        PosterToggleRow(
            title = stringResource(Res.string.settings_poster_hide_labels),
            checked = hideLabelsEnabled,
            onCheckedChange = onHideLabelsChange,
        )
    }
}

@Composable
private fun PosterLandscapeModeToggleRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    PosterToggleRow(
        title = stringResource(Res.string.settings_poster_landscape_mode),
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
private fun PosterToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )
    }
}

@Composable
private fun PosterCardLivePreview(
    widthDp: Int,
    cornerRadiusDp: Int,
) {
    val targetHeightDp = (widthDp * 3) / 2
    val previewFrameWidthDp = 140
    val previewFrameHeightDp = 210
    val animatedWidth = animateDpAsState(
        targetValue = widthDp.dp,
        animationSpec = tween(durationMillis = 280),
        label = "posterPreviewWidth",
    )
    val animatedHeight = animateDpAsState(
        targetValue = targetHeightDp.dp,
        animationSpec = tween(durationMillis = 280),
        label = "posterPreviewHeight",
    )
    val animatedCornerRadius = animateDpAsState(
        targetValue = cornerRadiusDp.dp,
        animationSpec = tween(durationMillis = 220),
        label = "posterPreviewCornerRadius",
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_poster_live_preview),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .width(previewFrameWidthDp.dp)
                    .height(previewFrameHeightDp.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(animatedWidth.value)
                        .height(animatedHeight.value)
                        .clip(RoundedCornerShape(animatedCornerRadius.value))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(animatedCornerRadius.value),
                        ),
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_poster_preview_width, widthDp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.settings_poster_preview_corner_radius, cornerRadiusDp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.settings_poster_preview_height, targetHeightDp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PosterStyleOptionRow(
    title: String,
    selectedValue: Int,
    options: List<PresetOption>,
    onSelected: (Int) -> Unit,
) {
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label
        ?: stringResource(Res.string.settings_poster_custom)

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_poster_option_with_value, title, selectedLabel),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option.value == selectedValue,
                    onClick = { onSelected(option.value) },
                    label = { Text(option.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }
    }
}

private data class PresetOption(
    val label: String,
    val value: Int,
)

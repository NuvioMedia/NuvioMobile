package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal data class LanguageSelectionOption(
    val value: String?,
    val label: String,
    val description: String? = null,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun LanguageSelectionDialog(
    title: String,
    options: List<LanguageSelectionOption>,
    selectedValue: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = title,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(options) { option ->
                val isSelected = option.value == selectedValue
                DialogOption(
                    text = option.label,
                    description = option.description?.takeIf { it.isNotBlank() },
                    selected = isSelected,
                    onClick = { onSelect(option.value) },
                )
            }
        }

        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_done),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun <T> SettingsChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    description: @Composable (T) -> String? = { null },
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = title,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                val optionDescription = description(option)
                DialogOption(
                    text = label(option),
                    description = optionDescription,
                    selected = isSelected,
                    onClick = { onSelect(option) },
                )
            }
        }

        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_done),
                onClick = onDismiss,
            )
        }
    }
}

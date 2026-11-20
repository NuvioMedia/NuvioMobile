package com.nuvio.app.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.nuvio.android.R
import com.nuvio.app.features.search.SearchHistoryRepository

/**
 * Search with the car's own keyboard. Submitting (or tapping a recent search) takes Nuvio straight to its
 * Search tab with the results; no need to find and tap Nuvio's search box first.
 */
internal class CarKeyboardScreen(
    ctx: CarContext,
    private val onSubmit: (String) -> Unit,
    private val onVoice: () -> Unit,
) : Screen(ctx) {

    private fun submit(text: String) {
        screenManager.pop()
        if (text.isNotBlank()) onSubmit(text.trim())
    }

    override fun onGetTemplate(): Template {
        val builder = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = Unit

            override fun onSearchSubmitted(searchText: String) = submit(searchText)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Search movies and shows")
            .setShowKeyboardByDefault(true)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.car_ic_mic)).build())
                            .setOnClickListener { onVoice() }
                            .build(),
                    )
                    .build(),
            )

        val recent = runCatching {
            SearchHistoryRepository.ensureLoaded()
            SearchHistoryRepository.uiState.value
        }.getOrDefault(emptyList()).filter { it.isNotBlank() }.distinct().take(MAX_RECENT)
        if (recent.isNotEmpty()) {
            val history = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.car_ic_search)).build()
            val list = ItemList.Builder()
            recent.forEach { query ->
                list.addItem(
                    Row.Builder()
                        .setTitle(query)
                        .setImage(history)
                        .setOnClickListener { submit(query) }
                        .build()
                )
            }
            builder.setItemList(list.build())
        }
        return builder.build()
    }

    private companion object {
        const val MAX_RECENT = 6
    }
}

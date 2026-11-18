package com.nuvio.app.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template

/** The car's own keyboard. The text is typed into the search box focused in Nuvio on the car screen. */
internal class CarKeyboardScreen(
    ctx: CarContext,
    private val onSubmit: (String) -> Unit,
) : Screen(ctx) {

    override fun onGetTemplate(): Template = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
        override fun onSearchTextChanged(searchText: String) = Unit

        override fun onSearchSubmitted(searchText: String) {
            screenManager.pop()
            if (searchText.isNotBlank()) onSubmit(searchText.trim())
        }
    })
        .setHeaderAction(Action.BACK)
        .setSearchHint("Search Nuvio")
        .setShowKeyboardByDefault(true)
        .build()
}

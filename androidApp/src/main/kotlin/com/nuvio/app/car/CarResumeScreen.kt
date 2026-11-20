package com.nuvio.app.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import com.nuvio.app.core.external.ExternalAppCommands
import com.nuvio.app.features.watchprogress.ContinueWatchingItem

/** "Continue watching?" when the car connects: one tap resumes the newest Continue Watching item. */
internal class CarResumeScreen(
    ctx: CarContext,
    private val item: ContinueWatchingItem,
) : Screen(ctx) {

    override fun onGetTemplate(): Template {
        val detail = listOfNotNull(
            item.subtitle.takeIf { it.isNotBlank() },
            item.episodeTitle?.takeIf { it.isNotBlank() && it != item.subtitle },
        ).joinToString(" · ")
        return MessageTemplate.Builder(if (detail.isEmpty()) item.title else "${item.title}\n$detail")
            .setTitle("Continue watching?")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Resume")
                    .setFlags(Action.FLAG_PRIMARY)
                    .setOnClickListener {
                        screenManager.pop()
                        ExternalAppCommands.send(ExternalAppCommands.Command.Resume(item))
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Not now")
                    .setOnClickListener { screenManager.pop() }
                    .build()
            )
            .build()
    }
}

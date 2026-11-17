package com.nuvio.app.features.player

import androidx.compose.ui.text.AnnotatedString

internal val StyledSubtitleCues.topText: AnnotatedString
    get() = textAt(SubtitleVerticalPosition.TOP)

internal val StyledSubtitleCues.bottomText: AnnotatedString
    get() = textAt(SubtitleVerticalPosition.BOTTOM)

private fun StyledSubtitleCues.textAt(vertical: SubtitleVerticalPosition): AnnotatedString =
    blocks.filter { it.vertical == vertical }.map { it.text }.joinLines()

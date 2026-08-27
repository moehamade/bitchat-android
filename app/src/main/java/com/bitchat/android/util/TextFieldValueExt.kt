package com.bitchat.android.util

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Replaces the current selection with a line break, or inserts one at the cursor when
 * nothing is selected.
 *
 * Slices on [TextRange.min]/[TextRange.max] rather than start/end. A selection made
 * backwards (Shift+Left, or dragging a handle right to left) keeps `start > end`, and
 * slicing on start/end duplicates the selected text instead of replacing it.
 */
fun TextFieldValue.withLineBreak(): TextFieldValue = TextFieldValue(
    text = text.replaceRange(selection.min, selection.max, "\n"),
    selection = TextRange(selection.min + 1)
)

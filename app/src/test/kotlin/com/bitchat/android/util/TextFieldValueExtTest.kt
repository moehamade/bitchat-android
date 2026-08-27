package com.bitchat.android.util

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `TextRange` keeps the direction of a selection: `start` is the anchor and `end` is the
 * moving cursor, so a backwards selection has `start > end`. These pin that
 * [withLineBreak] slices on min/max, because slicing on start/end reads correctly and
 * still duplicates the selection.
 */
class TextFieldValueExtTest {

    @Test
    fun `inserts a break at a collapsed cursor`() {
        val result = TextFieldValue("hello", TextRange(2)).withLineBreak()

        assertEquals("he\nllo", result.text)
        assertEquals(TextRange(3), result.selection)
    }

    @Test
    fun `replaces a forward selection`() {
        val result = TextFieldValue("hello", TextRange(1, 4)).withLineBreak()

        assertEquals("h\no", result.text)
        assertEquals(TextRange(2), result.selection)
    }

    @Test
    fun `replaces a backwards selection rather than duplicating it`() {
        // Shift+Left from index 4: the anchor stays right of the cursor.
        val backwards = TextFieldValue("hello", TextRange(4, 1))
        assertTrue("precondition: the range is reversed", backwards.selection.reversed)

        val result = backwards.withLineBreak()

        assertEquals("h\no", result.text)
        assertEquals(TextRange(2), result.selection)
    }

    @Test
    fun `breaks at the end of the text`() {
        val result = TextFieldValue("hello", TextRange(5)).withLineBreak()

        assertEquals("hello\n", result.text)
        assertEquals(TextRange(6), result.selection)
    }

    @Test
    fun `replaces a selection that covers everything`() {
        val result = TextFieldValue("hello", TextRange(5, 0)).withLineBreak()

        assertEquals("\n", result.text)
        assertEquals(TextRange(1), result.selection)
    }
}

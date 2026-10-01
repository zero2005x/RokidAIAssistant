package com.example.rokidglasses.ui

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class MeasuredTextPaginatorTest {
    private fun measurer() = TextMeasurer(createFontFamilyResolver(RuntimeEnvironment.getApplication()), Density(1f), LayoutDirection.Ltr)

    @Test
    fun multilineAnswersAndBlankLinesFitWithoutLosingCharacters() {
        val text = (1..30).joinToString("\n\n") { "$it. 測試 English 😀 answer" }
        val style = TextStyle(fontSize = 36.sp, lineHeight = 48.sp, fontFamily = FontFamily.Monospace)
        val measurer = measurer()
        val pages = MeasuredTextPaginator.paginate(text, 180, 144, style, measurer)
        assertTrue(pages.size > 1)
        assertEquals(text, pages.joinToString(""))
        pages.forEach { page ->
            val layout = measurer.measure(page, style, constraints = Constraints(maxWidth = 180))
            // A terminating newline creates a blank line which moves to the next page.
            val lastNonemptyLine = if (page.endsWith("\n")) layout.lineCount - 2 else layout.lineCount - 1
            if (lastNonemptyLine >= 0) assertTrue(layout.getLineBottom(lastNonemptyLine) <= 144)
            assertTrue(!Character.isHighSurrogate(page.last()))
        }
    }

    @Test
    fun increasingFontOrReducingViewportIncreasesPages() {
        val text = "長篇問題與回答 Hello world. ".repeat(30)
        val measurer = measurer()
        val small = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
        val large = TextStyle(fontSize = 36.sp, lineHeight = 48.sp)
        val a = MeasuredTextPaginator.paginate(text, 360, 240, small, measurer)
        val b = MeasuredTextPaginator.paginate(text, 360, 240, large, measurer)
        val c = MeasuredTextPaginator.paginate(text, 140, 100, large, measurer)
        assertTrue("small=${a.size}, large=${b.size}", b.size > a.size)
        assertTrue("wide=${b.size}, narrow=${c.size}", c.size > b.size)
        assertEquals(text, c.joinToString(""))
    }
}

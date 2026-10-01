package com.example.rokidglasses.ui

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints

/** Uses the same font, density and width as Text. Preserves every character, including blank lines. */
object MeasuredTextPaginator {
    fun paginate(text: String, widthPx: Int, heightPx: Int, style: TextStyle, measurer: TextMeasurer): List<String> {
        if (text.isEmpty()) return listOf("")
        val pages = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val remaining = text.substring(start)
            val layout = measurer.measure(remaining, style, constraints = Constraints(maxWidth = widthPx.coerceAtLeast(1)))
            var fittingLines = 0
            while (fittingLines < layout.lineCount && layout.getLineBottom(fittingLines) <= heightPx) fittingLines++
            // A single oversized line is still retained. The renderer allows scrolling when it cannot fit vertically.
            val lastLine = (fittingLines.coerceAtLeast(1) - 1).coerceAtMost(layout.lineCount - 1)
            var end = layout.getLineEnd(lastLine, visibleEnd = false)
            if (end <= 0) end = Character.charCount(remaining.codePointAt(0))
            pages += remaining.substring(0, end)
            start += end
        }
        return pages
    }
}

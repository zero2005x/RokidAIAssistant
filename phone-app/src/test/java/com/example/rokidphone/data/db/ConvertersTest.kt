package com.example.rokidphone.data.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class ConvertersTest {

    private val converters = Converters()

    @Test
    fun `dates round-trip through their epoch millis and null stays null`() {
        val date = Date(1_700_000_000_000)
        assertThat(converters.dateToTimestamp(date)).isEqualTo(1_700_000_000_000)
        assertThat(converters.fromTimestamp(1_700_000_000_000)).isEqualTo(date)
        assertThat(converters.dateToTimestamp(null)).isNull()
        assertThat(converters.fromTimestamp(null)).isNull()
    }

    @Test
    fun `string lists round-trip as JSON so separators inside elements survive`() {
        val values = listOf("plain", "has, comma", "  spaced  ", "\"quoted\"")
        val encoded = converters.toStringList(values)!!

        assertThat(converters.fromStringList(encoded)).containsExactlyElementsIn(values).inOrder()
        assertThat(converters.toStringList(emptyList())).isEqualTo("[]")
        assertThat(converters.fromStringList("[]")).isEmpty()
        assertThat(converters.toStringList(null)).isNull()
        assertThat(converters.fromStringList(null)).isNull()
    }

    @Test
    fun `legacy comma separated rows are still readable`() {
        // Rows written before the JSON encoding must not fail the whole Flow.
        assertThat(converters.fromStringList("a,b, ,c")).containsExactly("a", "b", "c").inOrder()
        assertThat(converters.fromStringList("")).isEmpty()
    }
}

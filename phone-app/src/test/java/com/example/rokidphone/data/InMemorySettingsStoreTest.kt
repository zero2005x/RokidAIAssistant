package com.example.rokidphone.data

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Contract tests for the non-persistent store used when Android Keystore is unavailable. */
class InMemorySettingsStoreTest {
    private fun store(): SharedPreferences =
        Class.forName("com.example.rokidphone.data.InMemorySharedPreferences")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance() as SharedPreferences

    @Test
    fun `all supported preference values round trip with defaults and stable snapshots`() {
        val prefs = store()
        assertThat(prefs.getString("absent", "default")).isEqualTo("default")
        assertThat(prefs.getInt("absent", 7)).isEqualTo(7)
        assertThat(prefs.getLong("absent", 8)).isEqualTo(8L)
        assertThat(prefs.getFloat("absent", 1.5f)).isEqualTo(1.5f)
        assertThat(prefs.getBoolean("absent", true)).isTrue()
        assertThat(prefs.getStringSet("absent", setOf("fallback"))).containsExactly("fallback")
        assertThat(prefs.edit().putString("text", "value").putInt("int", 4)
            .putLong("long", 5).putFloat("float", 2.5f).putBoolean("flag", true)
            .putStringSet("set", setOf("a", "b")).commit()).isTrue()
        assertThat(prefs.getString("text", null)).isEqualTo("value")
        assertThat(prefs.getInt("int", 0)).isEqualTo(4)
        assertThat(prefs.getLong("long", 0)).isEqualTo(5L)
        assertThat(prefs.getFloat("float", 0f)).isEqualTo(2.5f)
        assertThat(prefs.getBoolean("flag", false)).isTrue()
        assertThat(prefs.getStringSet("set", null)).containsExactly("a", "b")
        val snapshot = prefs.all
        prefs.edit().remove("text").apply()
        assertThat(prefs.contains("text")).isFalse()
        assertThat(snapshot["text"]).isEqualTo("value")
        prefs.edit().clear().putString("new", "kept").apply()
        assertThat(prefs.all).containsExactly("new", "kept")
    }

    @Test
    fun `reusing an editor never replays writes removals or clear operations`() {
        val prefs = store()
        val editor = prefs.edit().clear().putString("key", "old").remove("removed")
        editor.apply()
        prefs.edit().putString("key", "new").putString("removed", "restored").apply()
        editor.commit()
        assertThat(prefs.getString("key", null)).isEqualTo("new")
        assertThat(prefs.getString("removed", null)).isEqualTo("restored")
    }

    @Test
    fun `listeners see committed values and can be unregistered`() {
        val prefs = store()
        val observed = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { source, key ->
            observed += source.getString(key, null)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("key", "value").apply()
        prefs.edit().remove("key").apply()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("key", "unobserved").apply()
        assertThat(observed).containsExactly("value", null).inOrder()
    }

    @Test
    fun `independent editors preserve concurrent changes`() {
        val prefs = store()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks = (0..1).map { worker -> executor.submit {
                repeat(100) { index -> prefs.edit().putInt("$worker-$index", index).apply() }
            } }
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertThat(prefs.all).hasSize(200)
            for (worker in 0..1) for (index in 0 until 100) {
                assertThat(prefs.getInt("$worker-$index", -1)).isEqualTo(index)
            }
        } finally {
            executor.shutdownNow()
        }
    }
}

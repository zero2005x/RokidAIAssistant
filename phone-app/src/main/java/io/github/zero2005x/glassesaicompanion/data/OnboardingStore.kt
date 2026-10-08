package io.github.zero2005x.glassesaicompanion.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Remembers whether the user has read and accepted the first-launch notice
 * (unofficial app, optional glasses, where data goes, other people's privacy).
 *
 * Kept in plain preferences on purpose: it is not sensitive, and it must stay readable even if
 * the encrypted settings store has to be reset.
 */
class OnboardingStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun isAccepted(): Boolean = prefs.getInt(KEY_ACCEPTED_VERSION, 0) >= CURRENT_VERSION

    fun markAccepted() {
        prefs.edit().putInt(KEY_ACCEPTED_VERSION, CURRENT_VERSION).apply()
    }

    companion object {
        private const val PREFS_NAME = "onboarding"
        private const val KEY_ACCEPTED_VERSION = "accepted_version"

        /** Bump when the notice changes in a way users must read again. */
        const val CURRENT_VERSION = 1
    }
}

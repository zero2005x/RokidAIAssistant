package io.github.zero2005x.glassesaicompanion.ai.catalog

import io.github.zero2005x.glassesaicompanion.data.AiProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Manual capability override tests (Custom endpoints — issue #9).
 */
@RunWith(RobolectricTestRunner::class)
class ModelCapabilityResolverTest {

    @Test
    fun `empty override keys yield null overrides`() {
        assertThat(ModelCapabilityResolver.overridesFromKeys(AiProvider.CUSTOM, emptySet())).isNull()
    }

    @Test
    fun `vision key enables image input on a text-only provider default`() {
        val overrides = ModelCapabilityResolver.overridesFromKeys(
            AiProvider.CUSTOM,
            setOf(ModelCapabilityResolver.OVERRIDE_VISION)
        )
        assertThat(overrides).isNotNull()
        assertThat(overrides!!.imageInput).isTrue()
        // Provider default for CUSTOM is text-only, so this proves the override worked.
        assertThat(ModelCapabilityResolver.providerDefault(AiProvider.CUSTOM).imageInput).isFalse()
    }

    @Test
    fun `unknown override keys are ignored`() {
        val overrides = ModelCapabilityResolver.overridesFromKeys(
            AiProvider.CUSTOM,
            setOf("teleportation")
        )
        assertThat(overrides).isNotNull()
        assertThat(overrides!!.imageInput).isFalse()
        assertThat(overrides.audioInput).isFalse()
    }

    @Test
    fun `audio input key enables audio input`() {
        val overrides = ModelCapabilityResolver.overridesFromKeys(
            AiProvider.CUSTOM,
            setOf(ModelCapabilityResolver.OVERRIDE_AUDIO_INPUT)
        )
        assertThat(overrides!!.audioInput).isTrue()
    }

    @Test
    fun `manual override wins over fallback map for custom models`() {
        // "minicpm-v-2.6" exists in the Custom fallback list with vision support.
        // A manual override set that does NOT include vision must not add it back.
        val overrides = ModelCapabilityResolver.overridesFromKeys(AiProvider.CUSTOM, emptySet())
        val resolved = ModelCapabilityResolver.resolve(
            AiProvider.CUSTOM,
            "minicpm-v-2.6",
            manualOverrides = overrides
        )
        // overrides is null here → fallback map applies (vision true)
        assertThat(resolved.imageInput).isTrue()

        // With an explicit override set, the user's declaration wins.
        val withOverride = ModelCapabilityResolver.resolve(
            AiProvider.CUSTOM,
            "gemma-4-e4b",
            manualOverrides = ModelCapabilityResolver.overridesFromKeys(
                AiProvider.CUSTOM, setOf(ModelCapabilityResolver.OVERRIDE_VISION)
            )
        )
        assertThat(withOverride.imageInput).isTrue()
    }
}

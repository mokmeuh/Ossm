package com.ossm.remote.ui.modes

import androidx.annotation.StringRes
import com.ossm.remote.R
import com.ossm.remote.model.NATURAL_PATTERN_KEY
import com.ossm.remote.model.OssmPattern
import com.ossm.remote.model.PatternControlMode
import com.ossm.remote.model.VOICE_PATTERN_KEY

/** Les quatre réglages continus qu'un mode peut exposer à l'utilisateur. */
enum class ModeChannel { SPEED, DEPTH, STROKE, SENSATION }

/**
 * Carte d'identité d'un mode pour l'interface : ce qu'il expose, qui le pilote, ce qu'il attend.
 * C'est la seule source pour répondre à « que sait faire ce mode ? » ; l'écran de contrôle, le
 * sélecteur et les composants partagés s'y réfèrent au lieu de multiplier les tests sur la clé.
 * Décrit l'interface uniquement : aucune commande machine ni règle de sécurité ne vit ici.
 */
data class ModeDescriptor(
    val key: String,
    /** Une phrase qui dit ce que fait le mode (sélecteur, aide). */
    @StringRes val summaryRes: Int,
    /** Réglages continus proposés à l'utilisateur dans ce mode. */
    val channels: Set<ModeChannel>,
    /** Vrai si le téléphone génère le mouvement ; faux si la machine le fait seule. */
    val appDriven: Boolean,
    /** Vrai si le mode ne peut agir qu'une fois le Live prêt (`streamingReady`). */
    val needsStreaming: Boolean
) {
    fun exposes(channel: ModeChannel): Boolean = channel in channels
}

object ModeRegistry {
    private const val SIMPLE_STROKE_KEY = "simpleStroke"
    private const val AUTO_RANDOM_KEY = "autoRandom"
    private const val PROGRESSIVE_KEY = "progressive"
    private const val LIVE_KEY = "streamingLive"

    private val allChannels = setOf(ModeChannel.SPEED, ModeChannel.DEPTH, ModeChannel.STROKE, ModeChannel.SENSATION)
    private val withoutSensation = setOf(ModeChannel.SPEED, ModeChannel.DEPTH, ModeChannel.STROKE)

    private val byKey: Map<String, ModeDescriptor> = listOf(
        ModeDescriptor(
            key = AUTO_RANDOM_KEY,
            summaryRes = R.string.mode_summary_auto_random,
            channels = allChannels,
            appDriven = true,
            needsStreaming = false
        ),
        ModeDescriptor(
            key = NATURAL_PATTERN_KEY,
            summaryRes = R.string.mode_summary_natural,
            channels = allChannels,
            appDriven = true,
            needsStreaming = false
        ),
        ModeDescriptor(
            key = PROGRESSIVE_KEY,
            summaryRes = R.string.mode_summary_progressive,
            channels = allChannels,
            appDriven = true,
            needsStreaming = false
        ),
        ModeDescriptor(
            key = LIVE_KEY,
            summaryRes = R.string.mode_summary_live,
            channels = emptySet(),
            appDriven = true,
            needsStreaming = true
        ),
        ModeDescriptor(
            key = VOICE_PATTERN_KEY,
            summaryRes = R.string.mode_summary_voice,
            channels = emptySet(),
            appDriven = true,
            needsStreaming = true
        )
    ).associateBy { it.key }

    /**
     * Descripteur d'un mode. Les modes pilotés par la machine (moteur de course, simple) sont
     * décrits par leur famille : seul « Simple Stroke » n'a pas de curseur de sensation, comme
     * dans `shouldShowSensationSlider`.
     */
    fun of(pattern: OssmPattern?): ModeDescriptor? {
        if (pattern == null) return null
        byKey[pattern.key]?.let { return it }
        return when (pattern.mode) {
            PatternControlMode.STROKE_ENGINE -> ModeDescriptor(
                key = pattern.key,
                summaryRes = R.string.mode_summary_stroke_engine,
                channels = if (pattern.key == SIMPLE_STROKE_KEY) withoutSensation else allChannels,
                appDriven = false,
                needsStreaming = false
            )
            PatternControlMode.SIMPLE_PENETRATION -> ModeDescriptor(
                key = pattern.key,
                summaryRes = R.string.mode_summary_simple,
                channels = setOf(ModeChannel.SPEED),
                appDriven = false,
                needsStreaming = false
            )
            PatternControlMode.LAUNCH_ONLY -> ModeDescriptor(
                key = pattern.key,
                summaryRes = R.string.mode_summary_launch_only,
                channels = emptySet(),
                appDriven = false,
                needsStreaming = false
            )
            else -> null
        }
    }
}

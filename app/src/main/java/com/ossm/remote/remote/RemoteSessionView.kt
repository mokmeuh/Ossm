package com.ossm.remote.remote

/**
 * Lecture seule de la session Internet pour l'UI, séparée de `ControlUiState`.
 *
 * Pendant une session distante, les curseurs LOCAUX (vitesse, profondeur, sensation) ne pilotent
 * plus la machine : ils fixent l'ENVELOPPE ([speedMax], [depthMin], [depthMax], plage de sensation
 * [sensationMin]..[sensationMax] symétrique autour du neutre 0.5). Le navigateur agit
 * librement à l'intérieur ; les champs `remote*` sont les valeurs EFFECTIVES (après clamp)
 * qu'il a demandées. Le curseur local doit donc afficher l'enveloppe, jamais `remote*`.
 *
 * Avant la première commande du navigateur (`lastRemoteCommandAtMillis == null`), les champs
 * `remote*` reprennent l'état réel de la machine à l'ouverture de la session.
 */
data class RemoteSessionView(
    val active: Boolean = false,
    /** Vitesse maximale (0..1) que le navigateur peut atteindre. */
    val speedMax: Float = 0f,
    /** Bornes de profondeur (0..1) dans lesquelles le navigateur peut agir. */
    val depthMin: Float = 0f,
    val depthMax: Float = 1f,
    /**
     * Plage de sensation (0..1, 0.5 = neutre) permise au navigateur : symétrique autour du
     * neutre, [sensationMin] = 0.5 - d, [sensationMax] = 0.5 + d, d = intensité max locale.
     */
    val sensationMin: Float = 0f,
    val sensationMax: Float = 1f,
    /** Dernière vitesse effective demandée par le navigateur (déjà clampée par [speedMax]). */
    val remoteSpeed: Float? = null,
    val remoteDepthMin: Float? = null,
    val remoteDepthMax: Float? = null,
    /** Dernière sensation effective (déjà dans [sensationMin]..[sensationMax]). */
    val remoteSensation: Float? = null,
    val remotePatternKey: String? = null,
    /** Dernière position Live demandée par le navigateur, échelle du navigateur 0..100. */
    val remoteLivePercent: Int? = null,
    val lastRemoteCommandAtMillis: Long? = null,
) {
    /**
     * Session active mais maximum de vitesse à 0 : le navigateur ne peut rien faire bouger.
     * C'est voulu ; l'UI doit l'indiquer (« montez la vitesse maximale pour autoriser le distant »).
     */
    val motionBlockedByZeroMaximum: Boolean
        get() = active && speedMax <= 0f

    /** Intensité symétrique permise (0..0.5) : demi-largeur de la plage de sensation. */
    val sensationIntensity: Float
        get() = ((sensationMax - sensationMin) / 2f).coerceIn(0f, 0.5f)
}

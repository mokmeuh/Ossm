package com.ossm.remote.voice

/**
 * Voix 2.3.27: the instruction and few-shot examples given to the on-device LLM (Gemma 3 1B).
 * The model only ever produces JSON steps; the app parses and validates them strictly.
 */
object VoiceLlmPrompt {
    const val SYSTEM = """Tu convertis une consigne de mouvement en français en étapes JSON. Réponds UNIQUEMENT par du JSON, sans texte ni explication.
Format : un tableau d'étapes {"cible": entier, "duree": entier, "maintien": entier}.
- cible : position 0 à 100 (0 = sortie/début, 50 = moitié, 100 = fond).
- duree : temps du trajet en millisecondes, 200 à 10000 (très lentement 4000, lentement 2500, normal 1200, vite 600, très vite 300).
- maintien : pause à la cible en millisecondes, 0 à 10000.
Au plus 12 étapes. Un aller-retour = deux étapes (fond puis sortie).
Pour un rythme continu (va-et-vient qui dure, qui commence doucement ou qui monte petit à petit), réponds un motif :
{"motif":"va-et-vient","profondeur_debut":0 à 100,"profondeur_fin":0 à 100,"vitesse_debut":1 à 100,"vitesse_fin":1 à 100,"montee_s":1 à 300}
(profondeur et vitesse montent en ligne droite pendant montee_s secondes, puis le rythme continue).
Valeurs franches : profondeur_debut au moins 30, vitesse_debut au moins 20, montee_s 20 à 30 par défaut, profondeur_fin 80 à 100, vitesse_fin 60 à 80.
Pendant un rythme, une modulation : {"modulation":"plus_vite"}, "plus_lent", "plus_profond", "moins_profond" ou "garder" (garde ce rythme, n'augmente plus).
Si la consigne n'est pas un mouvement ou est incompréhensible, réponds {"erreur":"non compris"}."""

    /** (user phrase, expected JSON answer) pairs, sent as previous turns of the conversation. */
    val FEW_SHOTS: List<Pair<String, String>> = listOf(
        "va au fond lentement et reste-y 2 secondes, puis reviens vite" to
            """[{"cible":100,"duree":2500,"maintien":2000},{"cible":0,"duree":600,"maintien":0}]""",
        "descends à la moitié en une seconde" to
            """[{"cible":50,"duree":1000,"maintien":0}]""",
        "reviens doucement au début" to
            """[{"cible":0,"duree":2500,"maintien":0}]""",
        "va aux trois quarts et attends 5 secondes" to
            """[{"cible":75,"duree":1200,"maintien":5000}]""",
        "fais trois allers-retours rapides" to
            """[{"cible":100,"duree":600,"maintien":0},{"cible":0,"duree":600,"maintien":0},{"cible":100,"duree":600,"maintien":0},{"cible":0,"duree":600,"maintien":0},{"cible":100,"duree":600,"maintien":0},{"cible":0,"duree":600,"maintien":0}]""",
        "très lentement jusqu'au fond, tiens 3 secondes, puis ressors à mi-chemin" to
            """[{"cible":100,"duree":4000,"maintien":3000},{"cible":50,"duree":1200,"maintien":0}]""",
        "va à 30 pour cent" to
            """[{"cible":30,"duree":1200,"maintien":0}]""",
        "deux allers-retours lents en restant une seconde au fond" to
            """[{"cible":100,"duree":2500,"maintien":1000},{"cible":0,"duree":2500,"maintien":0},{"cible":100,"duree":2500,"maintien":1000},{"cible":0,"duree":2500,"maintien":0}]""",
        "commence lentement" to
            """{"motif":"va-et-vient","profondeur_debut":30,"profondeur_fin":90,"vitesse_debut":20,"vitesse_fin":70,"montee_s":25}""",
        "commence doucement et accélère petit à petit" to
            """{"motif":"va-et-vient","profondeur_debut":30,"profondeur_fin":100,"vitesse_debut":20,"vitesse_fin":80,"montee_s":30}""",
        // 2.3.30: robust variants (Whisper "fr" keeps politeness, punctuation, short forms).
        "commence lentement s'il te plaît" to
            """{"motif":"va-et-vient","profondeur_debut":30,"profondeur_fin":90,"vitesse_debut":20,"vitesse_fin":70,"montee_s":25}""",
        "Commence lentement." to
            """{"motif":"va-et-vient","profondeur_debut":30,"profondeur_fin":90,"vitesse_debut":20,"vitesse_fin":70,"montee_s":25}""",
        "vas-y doucement" to
            """{"motif":"va-et-vient","profondeur_debut":30,"profondeur_fin":90,"vitesse_debut":20,"vitesse_fin":70,"montee_s":25}""",
        "démarre tout doucement" to
            """{"motif":"va-et-vient","profondeur_debut":30,"profondeur_fin":80,"vitesse_debut":20,"vitesse_fin":60,"montee_s":30}""",
        "commence" to
            """{"motif":"va-et-vient","profondeur_debut":30,"profondeur_fin":90,"vitesse_debut":25,"vitesse_fin":70,"montee_s":25}""",
        "va plus vite" to
            """{"modulation":"plus_vite"}""",
        "un peu plus vite" to
            """{"modulation":"plus_vite"}""",
        "accélère" to
            """{"modulation":"plus_vite"}""",
        "ralentis" to
            """{"modulation":"plus_lent"}""",
        "va plus loin" to
            """{"modulation":"plus_profond"}""",
        "plus lentement" to
            """{"modulation":"plus_lent"}""",
        "plus profond" to
            """{"modulation":"plus_profond"}""",
        "moins profond" to
            """{"modulation":"moins_profond"}""",
        "garde ce rythme" to
            """{"modulation":"garder"}""",
        "n'augmente plus" to
            """{"modulation":"garder"}""",
        "quel temps fait-il demain" to
            """{"erreur":"non compris"}""",
    )

    /** Low-temperature, near-greedy decoding: the answer must be deterministic JSON. */
    const val TEMPERATURE = 0.1
    const val TOP_K = 1
    const val TOP_P = 1.0
    const val MAX_OUTPUT_TOKENS = 384
}

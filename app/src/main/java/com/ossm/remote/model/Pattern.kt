package com.ossm.remote.model

enum class PatternControlMode {
    STROKE_ENGINE,
    SIMPLE_PENETRATION,
    STREAMING,
    PROGRESSIVE,         // app-driven: full strokes, speed auto-ramps +1% per stroke
    LAUNCH_ONLY          // pattern that can be activated but exposes no parameter sliders
}

data class OssmPattern(
    val key: String,
    val name: String,
    val mode: PatternControlMode,
    val id: Int? = null
)

val KnownSimplePenetrationPattern = OssmPattern(
    key = "simplePenetration",
    name = "Simple",
    mode = PatternControlMode.SIMPLE_PENETRATION
)

// Replaces the broken "Simple": full back-and-forth strokes (via the stroke engine, so it
// oscillates safely from home), with the app auto-ramping speed +1% per stroke from a
// chosen start up to 100%. id=0 → uses Simple Stroke as the base motion.
val KnownProgressivePattern = OssmPattern(
    key = "progressive",
    name = "Progressif",
    mode = PatternControlMode.PROGRESSIVE,
    id = 0
)

val KnownStreamingPattern = OssmPattern(
    key = "streamingLive",
    name = "Live",
    mode = PatternControlMode.STREAMING
)

val KnownStrokeEnginePatterns = listOf(
    OssmPattern("simpleStroke",     "Simple Stroke",     PatternControlMode.STROKE_ENGINE, id = 0),
    OssmPattern("teasingPounding",  "Teasing Pounding",  PatternControlMode.STROKE_ENGINE, id = 1),
    OssmPattern("roboStroke",       "Robo Stroke",       PatternControlMode.STROKE_ENGINE, id = 2),
    OssmPattern("halfNHalf",        "Half n Half",       PatternControlMode.STROKE_ENGINE, id = 3),
    OssmPattern("deeper",           "Deeper",            PatternControlMode.STROKE_ENGINE, id = 4),
    OssmPattern("stopNGo",          "Stop n Go",         PatternControlMode.STROKE_ENGINE, id = 5),
    OssmPattern("insist",           "Insist",            PatternControlMode.STROKE_ENGINE, id = 6)
)

val KnownStrokeEnginePattern = KnownStrokeEnginePatterns.first()

// "Progressif" replaces the broken "Simple" in the picker. "Live" (streaming) kept.
val KnownFallbackPatterns: List<OssmPattern> =
    listOf(KnownProgressivePattern, KnownStreamingPattern) + KnownStrokeEnginePatterns

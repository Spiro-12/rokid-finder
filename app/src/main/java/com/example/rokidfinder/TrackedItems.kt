package com.example.rokidfinder

/**
 * The small set of objects RokidFinder tracks. Keep it tight (3–5) — every extra
 * item is another per-frame state machine, another bitmap ring buffer, and another
 * Vosk grammar phrase. All entries must be valid COCO labels that EfficientDet-Lite0
 * already detects well.
 *
 * - [key]    : short canonical id used in storage, broadcasts, and voice intents.
 * - [coco]   : exact label string returned by the model (case-insensitive match).
 * - [display]: how we show it to the user.
 * - [kind]   : "everyday" (Phase 1) or "safety" (Phase 4). Drives storage policy
 *              later — everyday items will eventually delete their snapshot once
 *              a text gist is written; safety items will keep the proof photo.
 */
object TrackedItems {
    data class Spec(
        val key: String,
        val coco: String,
        val display: String,
        val kind: String,
    )

    val ALL: List<Spec> = listOf(
        Spec(key = "phone",  coco = "cell phone", display = "phone",  kind = KIND_EVERYDAY),
        Spec(key = "laptop", coco = "laptop",     display = "laptop", kind = KIND_EVERYDAY),
        Spec(key = "cup",    coco = "cup",        display = "cup",    kind = KIND_EVERYDAY),
    )

    private val byKey: Map<String, Spec> = ALL.associateBy { it.key }
    private val byCoco: Map<String, Spec> = ALL.associateBy { it.coco.lowercase() }

    fun byKey(key: String): Spec? = byKey[key]
    fun byCocoLabel(label: String): Spec? = byCoco[label.lowercase()]

    /** All COCO labels we care about — used by ItemDetector to filter. */
    val COCO_LABELS: Set<String> = ALL.map { it.coco.lowercase() }.toSet()

    const val KIND_EVERYDAY = "everyday"
    const val KIND_SAFETY = "safety"
}

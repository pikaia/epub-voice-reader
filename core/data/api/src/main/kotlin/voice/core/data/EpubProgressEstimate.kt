package voice.core.data

private const val EPUB_CHARS_PER_SECOND = 15

/**
 * Estimated narration duration for the given character count, based on a fixed assumed narration
 * pace (~150 words/minute, ~6 characters/word). EPUB narration is synthesized on demand, so
 * there's no measured duration to use instead — this is the same technique e-readers use for
 * "12 min left in this chapter."
 */
public fun estimatedEpubDurationMs(characterCount: Int): Long =
  characterCount.toLong() * 1000L / EPUB_CHARS_PER_SECOND

/**
 * Inverse of [estimatedEpubDurationMs] — how many characters correspond to a given duration at
 * the same assumed pace. Used to resolve a scrubber seek target back to a sentence position.
 */
public fun estimatedEpubCharacterCount(durationMs: Long): Int =
  (durationMs * EPUB_CHARS_PER_SECOND / 1000L).toInt()

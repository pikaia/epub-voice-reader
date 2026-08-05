package voice.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

class EpubProgressEstimateTest {

  @Test
  fun `zero characters is zero duration`() {
    assertEquals(expected = 0L, actual = estimatedEpubDurationMs(0))
  }

  @Test
  fun `duration scales with character count at 15 chars per second`() {
    assertEquals(expected = 1_000L, actual = estimatedEpubDurationMs(15))
    assertEquals(expected = 10_000L, actual = estimatedEpubDurationMs(150))
  }

  @Test
  fun `character count is the inverse of duration`() {
    assertEquals(expected = 150, actual = estimatedEpubCharacterCount(10_000L))
    assertEquals(expected = 0, actual = estimatedEpubCharacterCount(0L))
  }
}

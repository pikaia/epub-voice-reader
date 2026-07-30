# EPUB AI Voice Reader — Design

## Summary

Fork [Voice](https://github.com/PaulWoitaschek/Voice) (GPLv3, Kotlin/Compose audiobook player) and add EPUB books
as a new library source type, narrated on-device by [Piper](https://github.com/rhasspy/piper) neural TTS, with a
read-along screen that highlights the sentence currently being spoken. Voice already provides folder scanning,
library UI, ExoPlayer-based playback, bookmarks, and storage — this design is additive, not a rewrite.

## Decisions

- **Base:** fork Voice rather than build standalone. EPUB+AI-voice is the novel part; audiobook playback,
  library management, and storage are reused as-is. This inherits GPLv3 — acceptable for this project, but
  blocks closed-source distribution if that's ever wanted.
- **TTS engine:** on-device Piper (ONNX Runtime), not a cloud API (ElevenLabs, Google, etc.). Fully offline,
  no per-character cost, no API key management. Voice quality is below ElevenLabs but was chosen deliberately
  over that tradeoff.
- **Conversion strategy:** rolling-buffer streaming synthesis — audio is synthesized just ahead of playback and
  cached to disk, not converted whole-book-upfront. Playback starts almost immediately regardless of book length.
- **Read-along UI:** yes — a dedicated screen renders chapter text and highlights the sentence being narrated,
  synced to playback.
- **Sync mechanism:** per-sentence synthesis. Each sentence is synthesized as its own short audio clip and clips
  play back-to-back (gapless) in ExoPlayer. "Current sentence" is simply "currently playing clip index" — exact
  sync by construction, no dependency on extracting timestamp/alignment data from Piper's internals. Tradeoff:
  slightly less natural prosody at sentence boundaries than one continuous synthesis pass; this can be revisited
  later (e.g. paragraph-level synthesis with alignment) as a quality upgrade, not required for v1.
- **Adding books:** EPUBs are picked up by extending Voice's existing folder-picker/scanner to recognize `.epub`
  files, rather than building a separate import flow. They appear in the same library as audiobooks.
- **Voice selection:** multiple Piper voices are selectable, chosen per book. Voices are not bundled in the APK;
  they're downloaded on demand from Piper's public voice repository and cached locally the first time a voice is
  selected. Keeps base app size small and scales to more voices/languages without a rebuild.

## Architecture

New/changed modules, following Voice's existing core/features layering (`AGENTS.md`: features depend only on
core + infrastructure; core never depends on features; no feature-to-feature deps):

- **`core:epub`** — EPUB parsing. Opens `.epub` files, extracts the chapter list and per-chapter plain text,
  splits text into sentences (e.g. via ICU `BreakIterator`). Pure Kotlin, no Android/UI dependency — easy to
  unit test against fixture files.
- **`core:tts`** — Piper integration. An ONNX Runtime wrapper for synthesis, a voice manager (lists available
  voices, downloads/caches them, tracks installed voices), and a sentence-clip cache (Room-backed, keyed by
  `(bookId, voiceId, sentenceId)`) with LRU eviction under a configurable storage cap.
- **`core:scanner`** (extended) — recognizes `.epub` files during folder scanning alongside existing audio
  formats.
- **`core:data`** (extended) — `Book` gains a source-type discriminator (`AUDIO` / `EPUB`) and a selected-voice
  reference. New tables: `EpubChapter(bookId, index, title)`, `EpubSentence(chapterId, index, text)`, and clip
  cache metadata.
- **`features:epubReader`** (new) — the read-along screen: renders chapter text, highlights the active sentence,
  drives playback controls. Depends only on `core:tts`, `core:epub`, `core:playback`, `core:data`. `bookOverview`
  routes here instead of `features:playbackScreen` when a book's source type is `EPUB`.
- **`features:settings`** (extended) — per-book voice picker and voice download/management UI.

## Data Flow

**Import → parse**
1. `core:scanner` finds an `.epub` in a watched folder, creates a `Book` row with `sourceType = EPUB`.
2. `core:epub` parses lazily on first open (not at scan time): extracts chapters and splits each into sentences,
   persisted to `EpubChapter`/`EpubSentence` so parsing happens once.

**Voice selection**
3. User picks a voice for the book (from Settings or a first-open prompt). If not yet downloaded, `core:tts`'s
   voice manager fetches it from Piper's voice repo and caches it under app-private storage. `Book.voiceId`
   stores the choice.

**Synthesis (rolling buffer)**
4. When the reader screen opens or playback resumes, the synthesis engine ensures a rolling window (e.g. next
   ~30 sentences) has cached clips, synthesizing missing ones in the background — keyed by
   `(bookId, voiceId, sentenceId)` so a voice change invalidates only affected clips.
5. Clips are queued into ExoPlayer as a windowed playlist (not the whole book at once). As playback advances,
   the window slides forward: clips fall out of the ExoPlayer queue but remain cached on disk per the eviction
   policy, and new clips are synthesized just ahead of playback.

**Playback → highlight**
6. `features:epubReader` observes ExoPlayer's current media item index, maps it to `(chapterId, sentenceId)`,
   and highlights that sentence in the rendered text.
7. Playback position persists as `(bookId, chapterId, sentenceId)` in place of the raw timestamp used for
   regular audiobooks; resuming re-derives the ExoPlayer window starting at that sentence.

**Storage/cache management:** clip cache is capped by a configurable size in Settings, LRU-evicted by
least-recently-played sentence, always keeping the current rolling window resident regardless of the cap.

## Error Handling & Edge Cases

- **DRM-protected EPUBs:** detected during parse (e.g. `META-INF/encryption.xml`) and marked unsupported with a
  clear message. No DRM-stripping — out of scope.
- **Malformed/unparseable EPUB:** parse failure marks the book "couldn't be read" rather than crashing the
  scanner.
- **Synthesis failure for a sentence:** skip and retry with backoff; a permanently-failing sentence plays
  silence and is visually marked rather than blocking the whole book.
- **Voice download failure:** surfaced in the voice picker with retry; the book keeps its previous voice (or
  stays unset) until a download succeeds.
- **Storage pressure:** eviction policy tightens (shrinks the rolling window / evicts more aggressively) before
  failing outright; if even the current window can't be written, surface a clear "storage full" error.
- **Very large books:** sentence counts can reach the tens of thousands. Chapter/sentence tables and the reader
  UI must page/lazily load text rather than holding a whole book's sentences in memory.
- **Mid-book voice change:** invalidates cached clips for that voice going forward from the current position.
  Already-played history is not retroactively re-synthesized; scrubbing back plays whatever is cached (possibly
  the prior voice) rather than forcing a re-synthesis pass.
- **Synthesis falling behind playback:** the player buffers/pauses rather than skipping narration, matching
  normal audio buffering UX.

## Testing

Following Voice's existing conventions (in-memory fakes, Molecule + Turbine for view state):

- `core:epub`: unit tests for sentence-splitting and chapter extraction against real-world `.epub` fixtures
  (varied structure, DRM-marked, malformed). Pure Kotlin, no instrumentation needed.
- `core:tts`: fake Piper engine + fake voice repository so synthesis-queue logic, rolling-window management,
  and cache eviction are tested without a real model or network.
- `core:scanner` / `core:data`: extend existing scanner tests to cover `.epub` detection and the new
  `sourceType` field.
- `features:epubReader`: Molecule/Turbine tests on the view model covering sentence-highlight-follows-playback,
  resume-from-position, and mid-book voice-change behavior, using fakes for `core:tts` and `core:playback`.
- Audio output quality itself is a manual/listening check, not asserted in CI.

## Non-Goals

- Cloud TTS providers (ElevenLabs, Google, etc.). On-device Piper only; no provider abstraction is being built
  preemptively.
- DRM-protected EPUB support.
- Whole-book upfront batch conversion mode — rolling-buffer streaming synthesis only.
- Per-word (as opposed to per-sentence) highlighting.
- Voice speed/pitch tuning UI, multi-language auto-detection, cross-device sync of synthesized clips.
- Editing/annotating EPUB text.

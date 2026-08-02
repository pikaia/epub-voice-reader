# Piper TTS Integration (Plan 3) — Design

## Summary

Build `core:tts`, the on-device neural text-to-speech module that Plan 4's reader screen will consume. Given an
`EpubSentence`'s text and a chosen voice, it produces a playable audio clip — synthesizing on demand via Piper
voice models run through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), and caching the result so repeated
playback (rewinds, resumes, replays) never re-runs inference on the same sentence. It also owns installing and
uninstalling voices. This plan builds and unit-tests `core:tts` in isolation — no reader UI, no ExoPlayer wiring,
no ties to a currently-open book. That orchestration is Plan 4's job.

This design elaborates the `core:tts` bullet in the parent spec
(`docs/superpowers/specs/2026-07-30-epub-ai-voice-reader-design.md`), which specified the module's role but not
its concrete implementation.

## Decisions

- **Inference engine: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)**, not a hand-rolled ONNX Runtime + JNI +
  espeak-ng integration. sherpa-onnx isn't on Maven Central; it's not published there at all (verified: a Maven
  Central search for `sherpa-onnx` returns nothing under `com.k2fsa.*`). The real, official artifact is the AAR
  attached to each GitHub release (e.g. `sherpa-onnx-1.13.4.aar`), built from their own `com.android.library`
  module (`android/SherpaOnnxAar/sherpa_onnx`) — it bundles both the native `.so` libraries for all ABIs *and* the
  Kotlin API source (`OfflineTts`, `OfflineTtsConfig`, `GeneratedAudio`, package `com.k2fsa.sherpa.onnx`) in one
  package, so it's a complete, self-contained dependency once resolved. They also maintain a `jitpack.yml` in the
  repo specifically to republish that release AAR via [JitPack](https://jitpack.io) — verified working directly:
  `curl https://jitpack.io/com/github/k2-fsa/sherpa-onnx/1.13.4/sherpa-onnx-1.13.4.pom` resolves immediately (no
  on-demand build wait). Add `maven(url = "https://jitpack.io")` to `settings.gradle.kts`'s
  `dependencyResolutionManagement.repositories`, then depend on `com.github.k2-fsa:sherpa-onnx:1.13.4`.
  Hand-rolling this integration ourselves would mean cross-compiling C++ for 4 ABIs and writing JNI glue — a large
  undertaking for no benefit over an actively maintained library, consistent with this codebase's existing pattern
  of using well-maintained dependencies (e.g. jsoup for EPUB parsing) rather than building infrastructure from
  scratch. espeak-ng (bundled, used for phonemization) is GPL-licensed; no new licensing concern since this fork
  already inherits Voice's GPLv3.
- **Voice catalog: small curated list, hardcoded, English-only to start.** The voice manager's "available voices"
  list is a short hardcoded set (name, language, download URL, size, SHA-256 checksum — computed once when the list
  entry is written) rather than dynamically fetching a full catalog. Proves the download/verify/cache/synthesize
  pipeline end-to-end without building a full catalog browser. Expanding to more voices later is just adding list
  entries; a cheap follow-up, not a blocker, and fits naturally as Plan 5 polish.
- **Voice source: sherpa-onnx's pre-converted voice packages, not raw Piper files.** Raw Piper voice files from
  Hugging Face (`rhasspy/piper-voices`, `.onnx` + `.onnx.json`) are not directly usable with sherpa-onnx's VITS
  runtime — they need offline conversion (extracting a `tokens.txt` sherpa-onnx actually reads, from the `.onnx.json`).
  sherpa-onnx publishes already-converted packages at
  `github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-{voice}.tar.bz2` — each one a
  self-contained archive with the `.onnx` model, `tokens.txt`, and its own `espeak-ng-data/` directory (confirmed by
  downloading and inspecting `vits-piper-en_US-amy-low.tar.bz2`). The curated catalog's download URLs point here,
  not at Hugging Face.
- **Sentence-clip caching is in scope for Plan 3, not deferred.** Synthesized audio is persisted (not
  synthesize-and-discard) because: repeat playback (rewind/resume/replay) shouldn't re-run inference on text
  already narrated; the read-along sync mechanism depends on each sentence being a discrete, addressable clip
  queued into ExoPlayer (per the parent design's per-sentence-synthesis decision); and background "synthesize
  ahead" buffering (Plan 4) requires clips to exist before they're needed. The cache is bounded via LRU eviction
  under a configurable size cap, not unlimited storage.
- **Persistence follows the Plan 2 convention**: new Room entities/DAOs/repos live in `core:data:api`/`core:data:impl`
  (not owned by `core:tts` itself), extending the existing `AppDb` migration chain. `core:tts` depends on
  `core:data:api` and consumes repos via Metro DI, never touching Room directly — mirroring how `core:scanner`
  already works.
- **No de-duplication of concurrent synthesis requests for the same sentence in Plan 3.** The clip-cache write is
  `REPLACE`, so a race between two callers still ends in one consistent row; true request de-duplication only
  matters once Plan 4's rolling-window scheduler exists and issues concurrent requests in practice. Add it then if
  it's a real problem.

## Architecture

New module: **`core:tts`**, following the existing `core:epub` → `core:scanner` layering pattern (pure/interface-first
core module, depends on `core:data:api` for persistence, no Android-UI dependency).

- **`SynthesisEngine`** (interface) / **`SherpaOnnxSynthesisEngine`** (impl) — `suspend fun synthesize(text: String,
  voice: InstalledVoice): SynthesisResult` where `SynthesisResult` is a sealed type (`Success(audio: ByteArray)` /
  `Failure(reason: String)`), never throws. Wraps sherpa-onnx's `OfflineTts`, constructed from an
  `OfflineTtsVitsModelConfig(model = voice.modelFile, tokens = voice.tokensFile, dataDir = voice.dataDir)`. Pure
  inference — no I/O beyond reading the model files already on disk.
- **`VoiceManager`** — `availableVoices(): List<VoiceCatalogEntry>` (hardcoded catalog merged with installed status),
  `suspend fun install(voiceId: String): InstallResult`, `suspend fun uninstall(voiceId: String)`. Downloads the
  voice's `.tar.bz2` to a temp file, verifies its SHA-256 checksum, extracts it (tar + bzip2, via Apache Commons
  Compress — not supported by Android's standard library) into `ttsVoices/{voiceId}/`, records an `InstalledVoice`
  row pointing at the extracted `.onnx` model, `tokens.txt`, and `espeak-ng-data/` directory, only on full success.
- **`SentenceClipCache`** — `suspend fun getOrSynthesize(bookId: BookId, voiceId: String, chapterIndex: Int, sentenceIndex: Int,
  text: String): ClipResult`. `(chapterIndex, sentenceIndex)` identifies the `EpubSentence` this clip is for — the
  same pair Plan 2's `EpubSentence` table is keyed on (`(bookId, chapterIndex, index)`), so a clip can always be
  traced back to the sentence it's a narration of. Checks `SentenceClipRepo` for an existing row (cache hit: touch
  `lastAccessedAt`, return); on miss, calls `SynthesisEngine`, writes the resulting WAV under app-private storage,
  evicts least-recently-accessed clips if over the configured size cap, inserts a `SentenceClip` row, returns the
  file.

### Data model (in `core:data`, extending `AppDb`)

- **`InstalledVoice`** (`@Entity`, primary key `voiceId: String`): `name: String`, `language: String`,
  `modelFile: File` (the `.onnx`), `tokensFile: File` (`tokens.txt`), `dataDir: File` (extracted `espeak-ng-data/`),
  `installedAt: Instant`, `sizeBytes: Long`. Reuses the existing `File` `TypeConverter` (already used by
  `BookContent.cover`).
- **`SentenceClip`** (`@Entity`, primary key `(bookId, voiceId, chapterIndex, sentenceIndex)` — matching
  `EpubSentence`'s own key shape from Plan 2): `file: File`, `sizeBytes: Long`, `lastAccessedAt: Instant` (drives
  LRU eviction).
- `VoiceRepo` / `SentenceClipRepo` interfaces in `core:data:api`, impls in `core:data:impl` with
  `@ContributesBinding(AppScope::class)` — same shape as `EpubBookRepo` from Plan 2.

### File storage

Following `CoverSaver`'s existing pattern (`File(context.filesDir, "bookCovers")`): voice model files under
`context.filesDir/ttsVoices/`, synthesized sentence clips under `context.filesDir/ttsClips/`.

## Data Flow

**Voice management**
1. `VoiceManager.availableVoices()` returns the hardcoded catalog cross-referenced against `VoiceRepo` for installed
   status.
2. `VoiceManager.install(voiceId)` downloads the voice's `.tar.bz2` to a temp file, verifies its SHA-256 checksum,
   extracts it into `ttsVoices/{voiceId}/`, writes an `InstalledVoice` row pointing at the extracted model/tokens/data
   paths. `uninstall(voiceId)` deletes `ttsVoices/{voiceId}/` and the row.

**Synthesis**
3. `SentenceClipCache.getOrSynthesize(bookId, voiceId, chapterIndex, sentenceIndex, text)`:
   - `SentenceClipRepo` lookup by `(bookId, voiceId, chapterIndex, sentenceIndex)` → hit: update `lastAccessedAt`, return the file.
   - Miss: `SynthesisEngine.synthesize(text, installedVoice)` (voice's model loaded via `VoiceRepo`) → write WAV to
     `ttsClips/` → insert `SentenceClip` row → return the file.
4. Before writing a new clip, if total cached size exceeds the configured cap (default **500 MB** — a plain
   in-code constant for this plan, not yet user-configurable; exposing it in Settings is Plan 5's job), delete
   least-recently-accessed `SentenceClip` rows/files until back under it.

**Explicitly out of scope for this plan**: no rolling-window "synthesize N sentences ahead" scheduler, no ExoPlayer
wiring, no concept of a "currently playing" book/window to protect during eviction. Plan 3 delivers the primitives;
Plan 4 orchestrates them against real playback.

## Error Handling & Edge Cases

- **Synthesis failure** (malformed text, model error): `SynthesisEngine`/`SentenceClipCache` return a typed
  `Failure` result rather than throwing. Deciding what the user sees (retry, skip, mark) is Plan 4's job.
- **Voice download failure** (network error, interruption): download writes to a temp file first; a failed or
  partial download never leaves a corrupt/half-installed voice behind. `install()` returns a typed result so a
  later plan's voice picker can surface retry.
- **Checksum mismatch**: treated as a download failure — reject, clean up the temp file, typed failure result.
- **Storage pressure**: if LRU eviction can't free enough space (disk genuinely full, or a single clip exceeds the
  whole cap), `getOrSynthesize` returns a typed failure rather than crashing.
- **Concurrent requests for the same sentence**: not de-duplicated (see Decisions) — acceptable for this plan, since
  nothing in Plan 3 itself issues concurrent requests for the same key.

## Testing

- **`SynthesisEngine`**: interface + `FakeSynthesisEngine` (canned audio or a typed failure on demand) for every
  other component's tests. `SherpaOnnxSynthesisEngine` loads native libraries and isn't unit-testable in this
  project's JVM/Robolectric suite (no `androidTest`/instrumented suite exists in this codebase currently) — it gets
  a manual on-device smoke check instead, the same way Plan 2's scanner work was verified on a real device.
- **`VoiceManager`**: unit tested with a fake downloader (no real network calls in tests) and a real in-memory Room
  DB for `VoiceRepo` (matching `EpubBookRepoImplTest`'s pattern from Plan 2) — covers install/uninstall,
  checksum-mismatch rejection, and partial-download cleanup leaving no half-installed state.
- **`SentenceClipCache`**: unit tested with `FakeSynthesisEngine` + real in-memory Room DB for `SentenceClipRepo` —
  covers cache hit/miss (engine called only on miss), LRU eviction actually removing least-recently-accessed
  rows/files under a small configured cap, and the storage-full typed-failure path.

## Non-Goals

- Fetching Piper's full multi-language `voices.json` catalog dynamically — deferred; the curated list is enough to
  prove the pipeline, and expanding it is a cheap follow-up.
- Rolling-window "synthesize ahead" scheduling, ExoPlayer integration, and any UI (voice picker, download progress,
  read-along highlighting) — all later plans' work.
- De-duplicating concurrent synthesis requests for the same sentence.
- Voice speed/pitch tuning, multi-language auto-detection (already non-goals in the parent design).

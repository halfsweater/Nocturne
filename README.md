# Nocturne

A realistic piano for Android phones: a multitouch 88-key keyboard, sixteen sample-based keyboard
instruments that play completely offline, and a low-latency native audio engine.

- **Keyboard** — true multitouch (chords, glissando, repeated notes), touch-position dynamics,
  sustain pedal (hold for momentary, tap to latch), octave buttons, a draggable 88-key overview for
  scrolling, adjustable key size, portrait and landscape.
- **Sounds** — 16 bundled instruments built from openly licensed multisample libraries
  (no synthesized beeps), plus an optional downloadable pack with 5 more.
- **Engine** — FluidSynth 2.6 (SoundFont) driven directly from an Oboe low-latency callback, three
  synth slots so loading an instrument never interrupts playing, and a post-mix chain
  (3-band EQ, studio bus compressor, stereo width, look-ahead limiter).
- **Sound controls** — volume, reverb, brightness, sustain length, Concert hall, Studio sound and
  Soft piano, all adjustable while you play.
- No account, no ads, no analytics. Preferences and favourites are stored on the device.

## Install

Download `releases/Nocturne-1.0.0.apk` (49.5 MB, Android 8.0+, arm64 / armv7 / x86_64) and open it on the phone;
allow "install unknown apps" for your browser or file manager when asked. SHA-256 is in the `.sha256` file next to it.

## Instruments

| Instrument | Built from | Licence |
|---|---|---|
| Yamaha-Inspired Grand | Salamander Grand (Yamaha C5), 4 velocity layers | CC BY 3.0 |
| Steinway-Inspired Concert Grand | VCSL Steinway Model B, 3 layers | CC0 |
| Bösendorfer-Inspired Grand | VCSL Kawai grand, 4 layers, dark/long voicing | CC0 |
| Fazioli-Inspired Concert Grand | Splendid Grand (Steinway Model D), 4 layers, brilliant voicing | Public domain |
| Warm Grand | YDP Grand (Yamaha Disklavier Pro), mellow-shifted, 3 layers | CC BY 3.0 |
| Bright Grand | Salamander, brightness-shifted samples + presence EQ | CC BY 3.0 |
| Soft Piano | Salamander pianissimo layers, felt voicing | CC BY 3.0 |
| Upright Piano | Upright Piano KW (Kawai upright), 2 layers | CC0 |
| Electric Piano | FreePats FM Piano 1 (DX7-style), 3 layers | CC0 |
| Rhodes-Style Electric Piano | GeneralUser GS tine EP, 3 layers | GeneralUser GS licence |
| Wurlitzer-Style Electric Piano | Greg Sullivan Wurlitzer EP200, 4 layers | CC BY 3.0 |
| Digital Stage Piano | Greg Sullivan Yamaha CP80, 4 layers | CC BY 3.0 |
| Church Organ | VCSL pipe organ (open diapason) | CC0 |
| Harpsichord | VCSL French harpsichord | CC0 |
| Soft Ambient Piano | Salamander pp layers + warm pad layer, large hall, chorus | as above |
| Cinematic Piano | Steinway B + sub-octave layer + slow strings, wide hall | as above |

Optional download (from the original host, SHA-256 pinned): **MuseScore General Keys** —
Honky-Tonk, Clavinet, Celesta, Vibraphone, Drawbar Organ (MIT).

Brand names describe a sound character only; Nocturne has no affiliation with any manufacturer.
Full credits are in the app (Library → ⓘ) and in `app/src/main/assets/licenses.txt`.

## Building

Requirements: JDK 17+, Android SDK (platform 35, build-tools 35), NDK `27.3.13750724`, CMake `3.31.6`.

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest        # keyboard geometry + catalog tests
```

The release build is signed with the repository key in `keystore/` (password `nocturne`) so
updates install over each other. Use your own keystore before distributing publicly.

## Architecture

```
app/src/main/
  cpp/engine/Engine.*          synth pool (3 FluidSynth instances), lock-free event queue, post-mix DSP
  cpp/engine/Sf3Transcoder.*   SF3 (Ogg Vorbis) -> SF2 conversion with loop-seam healing (stb_vorbis)
  cpp/jni/AudioOutput.*        Oboe stream: AAudio/OpenSL, exclusive low-latency, auto-reopen, xrun-adaptive buffer
  cpp/jni/NativeBridge.cpp     JNI surface
  cpp/third_party/fluidsynth   FluidSynth 2.6.1 (LGPL-2.1+, built as libfluidsynth.so) — see NOCTURNE_PATCHES.md
  assets/instruments.json      instrument catalogue: layers + voicing (shared with the host test harness)
  assets/soundpacks/*.sf3      bundled sample packs (41 MB)
  java/app/nocturne/...        Compose UI, keyboard View, view model, pack manager, settings
tools/soundfonts/              reproducible pack builder (trim, normalise, phase-matched tail loops, SF3)
tools/hosttest/                engine compiled for the host + Python harness that renders and measures every instrument
```

**Latency path.** Touches go from a plain Android `View` with unbuffered dispatch straight into a
lock-free queue; the Oboe callback drains it at the start of every buffer. The engine adds 64
samples (1.3 ms) of limiter look-ahead; samples are trimmed to ~1 ms of pre-roll. Total
touch-to-sound latency is dominated by the device's audio path (typically 10–30 ms on AAudio
devices with a low-latency output).

**Switching instruments while playing.** A new instrument loads into an idle synth slot on a
background thread; when it is ready, new notes go to it while notes still sounding (held keys,
pedal) finish on the old slot. Audio never stops during a switch.

**Memory and storage.** Packs are stored compressed in the APK. The first time an instrument is
played its pack is unpacked once to app storage (≈0.5–1.5 s); favourites are unpacked in the
background. FluidSynth streams samples with dynamic loading, so only the active instrument is in
RAM (roughly 25–80 MB). If every bundled instrument has been used, unpacked packs occupy about
390 MB of app storage.

## Rebuilding the sound packs

See [`tools/soundfonts/README.md`](tools/soundfonts/README.md).

## Verification done so far

Measured on the host build of the exact engine code (`tools/hosttest/evaluate.py`), all 21 instruments:

- note-on to first audible sample: 1.6–3.9 ms inside the engine (ambient pad: 10.9 ms, by design)
- 0 clicks detected across sustained notes, loop wraps, pedal and voice release
- loudness matched to −24 dBFS RMS ±0.5 dB (Soft Piano intentionally −26)
- every pair of instruments differs by ≥3.4 dB mean spectral difference (median 10.7 dB)
- all 1,345 sample loops seamless after decoding (wrap curvature within the waveform's own 99.9th percentile)

Android emulator (API 31 x86_64, software CPU emulation — no hardware acceleration available in
the build environment):

- app installs and starts; AAudio output stream opens
- all 16 bundled instruments unpack, load and sound when a key is pressed (engine voice counts
  and output peaks read back from the running app)
- three simultaneous fingers injected through the kernel touchscreen device → 3 notes sounding
  together (6 stereo voices), all released when the fingers lift
- instrument switching via next/previous; layered instruments sound all their layers

**Not yet verified on a physical Android device.** The emulator cannot judge latency or sound
quality (it emulates the CPU in software and under-runs constantly), so real-hardware latency,
touch feel and CPU headroom still need to be confirmed on a phone.

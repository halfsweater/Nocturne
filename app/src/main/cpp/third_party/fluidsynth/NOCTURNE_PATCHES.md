# FluidSynth (vendored)

Upstream: https://github.com/FluidSynth/fluidsynth tag `v2.6.1`, LGPL-2.1-or-later (see LICENSE).
FluidSynth is built as a separate shared library (`libfluidsynth.so`) so it can be replaced.

Removed from the upstream tree: `doc/`, `test/`, `sf2/`, `contrib/`, the signalsmith
submodule (disabled via `enable-signalsmith=off`). `gcem/` contains only gcem's headers.

Local changes:
1. `CMakeLists.txt`: `test` and `doc` subdirectories are only added when present.
2. `src/CMakeLists.txt`: on Android the library is built without VERSION/SOVERSION so the
   SONAME is `libfluidsynth.so` (required for APK packaging).

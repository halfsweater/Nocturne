#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>

namespace nocturne {

/**
 * Converts a SoundFont 3 file (Ogg Vorbis compressed samples, MuseScore layout)
 * into a plain SoundFont 2 file that FluidSynth can stream with dynamic sample
 * loading. Uncompressed SF2 input is copied through unchanged.
 *
 * The output is written to `outPath + ".part"` and atomically renamed when
 * complete, so an interrupted conversion never leaves a corrupt cache file.
 */
class Sf3Transcoder {
public:
    using Progress = std::function<void(float)>;

    static bool transcode(const uint8_t *data, size_t size, const std::string &outPath,
                          const Progress &progress, std::string &error);

    static bool transcodeFile(const std::string &inPath, const std::string &outPath,
                              const Progress &progress, std::string &error);
};

} // namespace nocturne

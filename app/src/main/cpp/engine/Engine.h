#pragma once

#include <array>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include <fluidsynth.h>

#include "Dsp.h"
#include "EventQueue.h"

namespace nocturne {

struct LayerSpec {
    int file = 0;            // index into InstrumentSpec::files
    int bank = 0;
    int program = 0;
    int transpose = 0;       // semitones
    float gainDb = 0.0f;
    float velocityScale = 1.0f;
    int velocityOffset = 0;
};

/** Everything that defines how one selectable instrument sounds. */
struct InstrumentSpec {
    std::vector<std::string> files;   // SF2 files (already transcoded)
    std::vector<LayerSpec> layers;    // up to kMaxLayers, one MIDI channel each

    // Synth-level voicing (applied as generator offsets / effect settings)
    float filterCents = 0;      // added to initialFilterFc (negative = darker)
    float filterQ = 0;          // added to initialFilterQ (centibels)
    float decayTimecents = 0;   // added to the volume envelope decay (longer sustain)
    float releaseTimecents = 0; // added to the volume envelope release
    float reverbSend = 0.25f;   // 0..1
    float chorusSend = 0.0f;    // 0..1
    float roomSize = 0.55f, damping = 0.35f, reverbWidth = 0.8f, reverbLevel = 0.75f;
    float chorusDepthMs = 4.0f, chorusSpeedHz = 0.35f, chorusLevel = 1.2f;
    int chorusVoices = 3;

    // Post-mix voicing
    float lowShelfDb = 0, presenceDb = 0, highShelfDb = 0;
    float stereoWidth = 1.0f;
    float outputGainDb = 0;
};

/** User-facing sound controls. All values are normalised. */
struct MasterParams {
    float volume = 0.8f;        // 0..1
    float brightness = 0.0f;    // -1..1
    float reverb = 0.5f;        // 0..1 (0.5 = instrument default)
    float sustain = 0.5f;       // 0..1 (0.5 = natural decay)
    bool concertHall = false;
    bool studio = false;
    bool soft = false;
};

class Engine {
public:
    static constexpr int kSlots = 3;
    static constexpr int kMaxLayers = 4;
    static constexpr int kBlock = 256;

    Engine();
    ~Engine();

    /** Creates the synth pool. Must be called before render(). */
    bool init(int sampleRate);
    int sampleRate() const { return sampleRate_; }

    // ---- Control thread API (UI / loader) -------------------------------------------------
    void noteOn(int key, int velocity);
    void noteOff(int key);
    void setSustain(bool down);
    void allNotesOff();
    void setMasterParams(const MasterParams &p);

    /**
     * Loads an instrument into a free synth slot and switches to it once ready.
     * Blocking (file I/O); call from a background thread. The previously active
     * instrument keeps playing until its notes are released, so switching
     * never interrupts audio.
     */
    bool loadInstrument(const InstrumentSpec &spec, std::string &error);

    // ---- Audio thread API ---------------------------------------------------------------
    void render(float *interleavedStereo, int frames);

    // ---- Diagnostics --------------------------------------------------------------------
    int activeVoices() const { return activeVoices_.load(std::memory_order_relaxed); }
    /** Highest voice count rendered since the previous call (diagnostics). */
    int takePeakVoices() { return peakVoices_.exchange(0, std::memory_order_relaxed); }
    float peakLevel() const { return peak_.load(std::memory_order_relaxed); }

private:
    enum SlotState : int { Idle = 0, Loading = 1, Ready = 2, Releasing = 3, Fading = 4 };

    struct Slot {
        fluid_settings_t *settings = nullptr;
        fluid_synth_t *synth = nullptr;
        std::atomic<int> state{Idle};
        InstrumentSpec spec;
        std::vector<std::string> loadedFiles;   // parallel to sfontIds
        std::vector<int> sfontIds;
        int heldKeys = 0;
        float gain = 1.0f;
        float gainTarget = 1.0f;
        uint64_t releasedAt = 0;
        std::array<int, kMaxLayers> layerChannelProgram{};
    };

    void processEvent(const Event &e);
    void applyVoicing(Slot &slot);
    void applySustain(Slot &slot, bool down);
    void releaseKey(int key);
    void updatePostChain(bool immediate);
    int claimIdleSlot();

    int sampleRate_ = 48000;
    std::array<Slot, kSlots> slots_;
    int active_ = -1;
    std::array<int8_t, 128> keySlot_{};
    bool pedal_ = false;
    uint64_t frameCounter_ = 0;

    EventQueue<1024> queue_;
    std::mutex loaderMutex_;
    std::mutex paramsMutex_;
    MasterParams params_;          // guarded by paramsMutex_ (copied by audio thread on event)
    MasterParams audioParams_;     // audio thread copy

    // Post-mix chain (audio thread only)
    dsp::Biquad lowShelf_, presence_, highShelf_;
    float curLow_ = 0, curPres_ = 0, curHigh_ = 0;
    float tgtLow_ = 0, tgtPres_ = 0, tgtHigh_ = 0;
    dsp::Compressor compressor_;
    dsp::Limiter limiter_;
    dsp::Smoothed volume_, width_;

    std::vector<float> mixL_, mixR_, tmpL_, tmpR_;
    std::atomic<int> activeVoices_{0};
    std::atomic<int> peakVoices_{0};
    std::atomic<float> peak_{0};
};

} // namespace nocturne

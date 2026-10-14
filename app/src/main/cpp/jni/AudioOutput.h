#pragma once

#include <atomic>
#include <memory>
#include <mutex>

#include <oboe/Oboe.h>

#include "engine/Engine.h"

namespace nocturne {

/**
 * Low-latency Oboe output stream that pulls audio from the Engine.
 * Uses AAudio (or OpenSL ES on old devices) in exclusive, low-latency mode
 * and re-opens itself automatically when the audio device changes.
 */
class AudioOutput : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    explicit AudioOutput(Engine &engine) : engine_(engine) {}
    ~AudioOutput() override { close(); }

    /** Opens the stream; returns the device sample rate or 0 on failure. */
    int open(int preferredRate);
    bool start();
    void stop();
    void close();

    int sampleRate() const { return sampleRate_; }
    int framesPerBurst() const { return framesPerBurst_.load(); }
    int bufferSizeFrames() const { return bufferFrames_.load(); }
    int xRuns() const { return xruns_.load(); }
    double latencyMs();
    bool isRunning() const { return running_.load(); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    bool openLocked();
    void tuneBuffer(oboe::AudioStream *stream);

    Engine &engine_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::mutex lock_;
    int sampleRate_ = 0;
    std::atomic<int> framesPerBurst_{0};
    std::atomic<int> bufferFrames_{0};
    std::atomic<int> xruns_{0};
    std::atomic<bool> running_{false};
    std::atomic<bool> wantRunning_{false};
    int lastXrunCount_ = 0;
    int callbackCount_ = 0;
};

} // namespace nocturne

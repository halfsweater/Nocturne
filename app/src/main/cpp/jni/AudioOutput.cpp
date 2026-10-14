#include "AudioOutput.h"

#include <android/log.h>

#define LOG_TAG "NocturneAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace nocturne {

int AudioOutput::open(int preferredRate) {
    std::lock_guard<std::mutex> l(lock_);
    sampleRate_ = preferredRate;
    return openLocked() ? sampleRate_ : 0;
}

bool AudioOutput::openLocked() {
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(oboe::Usage::Game)          // Game usage gets the lowest-latency path on most devices
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    if (sampleRate_ > 0) {
        b.setSampleRate(sampleRate_);
        b.setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
    }
    oboe::Result r = b.openStream(stream_);
    if (r != oboe::Result::OK) {
        LOGW("exclusive open failed (%s), retrying shared", oboe::convertToText(r));
        b.setSharingMode(oboe::SharingMode::Shared);
        r = b.openStream(stream_);
    }
    if (r != oboe::Result::OK) {
        LOGW("openStream failed: %s", oboe::convertToText(r));
        stream_.reset();
        return false;
    }
    if (sampleRate_ <= 0) sampleRate_ = stream_->getSampleRate();
    framesPerBurst_ = stream_->getFramesPerBurst();
    // Start with two bursts of buffering; tuneBuffer() grows it only if underruns occur.
    stream_->setBufferSizeInFrames(framesPerBurst_ * 2);
    bufferFrames_ = stream_->getBufferSizeInFrames();
    lastXrunCount_ = 0;
    LOGI("stream open: %d Hz, burst %d, buffer %d, api %s, sharing %s", stream_->getSampleRate(),
         framesPerBurst_.load(), bufferFrames_.load(),
         stream_->getAudioApi() == oboe::AudioApi::AAudio ? "AAudio" : "OpenSLES",
         stream_->getSharingMode() == oboe::SharingMode::Exclusive ? "exclusive" : "shared");
    return true;
}

bool AudioOutput::start() {
    std::lock_guard<std::mutex> l(lock_);
    wantRunning_ = true;
    if (!stream_ && !openLocked()) return false;
    const bool ok = stream_->requestStart() == oboe::Result::OK;
    running_ = ok;
    return ok;
}

void AudioOutput::stop() {
    std::lock_guard<std::mutex> l(lock_);
    wantRunning_ = false;
    if (stream_) stream_->requestStop();
    running_ = false;
}

void AudioOutput::close() {
    std::lock_guard<std::mutex> l(lock_);
    wantRunning_ = false;
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
    running_ = false;
}

double AudioOutput::latencyMs() {
    std::lock_guard<std::mutex> l(lock_);
    if (!stream_) return -1;
    auto r = stream_->calculateLatencyMillis();
    return r ? r.value() : -1;
}

void AudioOutput::tuneBuffer(oboe::AudioStream *stream) {
    auto x = stream->getXRunCount();
    if (!x) return;
    const int count = x.value();
    xruns_ = count;
    if (count > lastXrunCount_) {
        lastXrunCount_ = count;
        const int burst = stream->getFramesPerBurst();
        const int current = stream->getBufferSizeInFrames();
        if (current + burst <= stream->getBufferCapacityInFrames() && current < burst * 8) {
            stream->setBufferSizeInFrames(current + burst);
            bufferFrames_ = stream->getBufferSizeInFrames();
        }
    }
}

oboe::DataCallbackResult AudioOutput::onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) {
    engine_.render(static_cast<float *>(audioData), numFrames);
    if (++callbackCount_ % 64 == 0) tuneBuffer(stream);
    return oboe::DataCallbackResult::Continue;
}

void AudioOutput::onErrorAfterClose(oboe::AudioStream *, oboe::Result error) {
    // Typically ErrorDisconnected: headphones plugged/unplugged or Bluetooth route change.
    LOGW("stream closed: %s; reopening", oboe::convertToText(error));
    std::lock_guard<std::mutex> l(lock_);
    stream_.reset();
    if (openLocked() && wantRunning_) {
        running_ = stream_->requestStart() == oboe::Result::OK;
    } else {
        running_ = false;
    }
}

} // namespace nocturne

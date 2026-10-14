#include "Engine.h"

#include <chrono>
#include <cmath>
#include <cstring>
#include <thread>

namespace nocturne {

namespace {
inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline float dbToGain(float db) { return std::pow(10.0f, db / 20.0f); }
} // namespace

Engine::Engine() { keySlot_.fill(-1); }

Engine::~Engine() {
    for (auto &s : slots_) {
        if (s.synth) delete_fluid_synth(s.synth);
        if (s.settings) delete_fluid_settings(s.settings);
    }
}

bool Engine::init(int sampleRate) {
    sampleRate_ = sampleRate;
    for (auto &s : slots_) {
        s.settings = new_fluid_settings();
        if (!s.settings) return false;
        fluid_settings_setnum(s.settings, "synth.sample-rate", double(sampleRate));
        fluid_settings_setint(s.settings, "synth.polyphony", 192);   // headroom for mid-range phones; FluidSynth steals released voices first
        fluid_settings_setint(s.settings, "synth.midi-channels", 16);
        fluid_settings_setint(s.settings, "synth.threadsafe-api", 0);
        fluid_settings_setint(s.settings, "synth.dynamic-sample-loading", 1);
        fluid_settings_setint(s.settings, "synth.lock-memory", 0);
        fluid_settings_setint(s.settings, "synth.cpu-cores", 1);
        fluid_settings_setint(s.settings, "synth.reverb.active", 1);
        fluid_settings_setint(s.settings, "synth.chorus.active", 1);
        fluid_settings_setnum(s.settings, "synth.gain", 0.5);
        s.synth = new_fluid_synth(s.settings);
        if (!s.synth) return false;
        // FluidSynth enables the default "MIDI Note-On velocity to filter" modulator; our
        // packs override it per instrument where needed, so nothing else to set up here.
    }
    mixL_.assign(kBlock, 0.0f);
    mixR_.assign(kBlock, 0.0f);
    tmpL_.assign(kBlock, 0.0f);
    tmpR_.assign(kBlock, 0.0f);
    compressor_.setSampleRate(float(sampleRate));
    limiter_.setSampleRate(float(sampleRate), std::max(32, sampleRate / 750));
    volume_.setTimeConstant(float(sampleRate), 0.03f);
    width_.setTimeConstant(float(sampleRate), 0.05f);
    volume_.value = volume_.target = 0.64f;
    width_.value = width_.target = 1.0f;
    updatePostChain(true);
    return true;
}

// ------------------------------------------------------------------ control thread

void Engine::noteOn(int key, int velocity) {
    if (key < 0 || key > 127) return;
    queue_.push({Event::NoteOn, int16_t(key), int16_t(clampi(velocity, 1, 127))});
}

void Engine::noteOff(int key) {
    if (key < 0 || key > 127) return;
    queue_.push({Event::NoteOff, int16_t(key), 0});
}

void Engine::setSustain(bool down) { queue_.push({Event::Sustain, int16_t(down ? 1 : 0), 0}); }

void Engine::allNotesOff() { queue_.push({Event::AllNotesOff, 0, 0}); }

void Engine::setMasterParams(const MasterParams &p) {
    {
        std::lock_guard<std::mutex> lock(paramsMutex_);
        params_ = p;
    }
    queue_.push({Event::ParamsChanged, 0, 0});
}

int Engine::claimIdleSlot() {
    for (int i = 0; i < kSlots; ++i) {
        int expected = Idle;
        if (slots_[i].state.compare_exchange_strong(expected, Loading, std::memory_order_acq_rel)) return i;
    }
    return -1;
}

bool Engine::loadInstrument(const InstrumentSpec &spec, std::string &error) {
    std::lock_guard<std::mutex> loaderLock(loaderMutex_);
    if (spec.layers.empty() || spec.layers.size() > size_t(kMaxLayers)) {
        error = "instrument needs 1-4 layers";
        return false;
    }
    int idx = claimIdleSlot();
    for (int attempt = 0; idx < 0 && attempt < 300; ++attempt) {
        if (attempt % 50 == 0) queue_.push({Event::FadeOldest, 0, 0});
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
        idx = claimIdleSlot();
    }
    if (idx < 0) {
        error = "audio engine busy";
        return false;
    }
    Slot &s = slots_[idx];

    // Resolve SoundFont ids, re-using files this slot already parsed.
    std::vector<int> ids;
    for (const auto &path : spec.files) {
        int id = -1;
        for (size_t i = 0; i < s.loadedFiles.size(); ++i)
            if (s.loadedFiles[i] == path) id = s.sfontIds[i];
        if (id < 0) {
            id = fluid_synth_sfload(s.synth, path.c_str(), 0);
            if (id == FLUID_FAILED) {
                error = "could not load " + path;
                s.state.store(Idle, std::memory_order_release);
                return false;
            }
            s.loadedFiles.push_back(path);
            s.sfontIds.push_back(id);
        }
        ids.push_back(id);
    }

    for (int ch = 0; ch < kMaxLayers; ++ch) {
        fluid_synth_cc(s.synth, ch, 64, 0);
        if (ch < int(spec.layers.size())) {
            const LayerSpec &l = spec.layers[ch];
            if (l.file < 0 || l.file >= int(ids.size()) ||
                fluid_synth_program_select(s.synth, ch, ids[l.file], l.bank, l.program) != FLUID_OK) {
                error = "missing preset " + std::to_string(l.bank) + ":" + std::to_string(l.program);
                s.state.store(Idle, std::memory_order_release);
                return false;
            }
            const int cc7 = clampi(int(std::lround(127.0 * std::pow(10.0, l.gainDb / 40.0))), 0, 127);
            fluid_synth_cc(s.synth, ch, 7, cc7);
            fluid_synth_cc(s.synth, ch, 11, 127);
            fluid_synth_cc(s.synth, ch, 10, 64);
        } else {
            fluid_synth_unset_program(s.synth, ch);
        }
    }

    // Keep at most a handful of parsed files per slot.
    while (s.loadedFiles.size() > 6) {
        size_t victim = s.loadedFiles.size();
        for (size_t i = 0; i < s.loadedFiles.size(); ++i) {
            bool used = false;
            for (int id : ids) used |= (s.sfontIds[i] == id);
            if (!used) { victim = i; break; }
        }
        if (victim == s.loadedFiles.size()) break;
        fluid_synth_sfunload(s.synth, s.sfontIds[victim], 0);
        s.loadedFiles.erase(s.loadedFiles.begin() + long(victim));
        s.sfontIds.erase(s.sfontIds.begin() + long(victim));
    }

    s.spec = spec;
    s.heldKeys = 0;
    s.gain = 1.0f;
    s.gainTarget = 1.0f;
    s.state.store(Ready, std::memory_order_release);
    queue_.push({Event::Activate, int16_t(idx), 0});
    return true;
}

// ------------------------------------------------------------------ audio thread

void Engine::applySustain(Slot &slot, bool down) {
    const int layers = int(slot.spec.layers.size());
    for (int ch = 0; ch < layers; ++ch) fluid_synth_cc(slot.synth, ch, 64, down ? 127 : 0);
}

void Engine::applyVoicing(Slot &slot) {
    const MasterParams &p = audioParams_;
    const InstrumentSpec &sp = slot.spec;
    const float filter = sp.filterCents + (p.brightness < 0 ? p.brightness * 2800.0f : p.brightness * 500.0f) +
                         (p.soft ? -1300.0f : 0.0f);
    const float decay = sp.decayTimecents + (p.sustain - 0.5f) * 2.0f * 1500.0f;
    const float release = sp.releaseTimecents + (p.sustain - 0.5f) * 2.0f * 800.0f;
    const float rsend = clampf(sp.reverbSend * (p.reverb * 2.0f) * (p.concertHall ? 1.45f : 1.0f) *
                                   (p.studio ? 0.55f : 1.0f), 0.0f, 1.0f);
    const float csend = clampf(sp.chorusSend, 0.0f, 1.0f);
    const int layers = int(sp.layers.size());
    for (int ch = 0; ch < layers; ++ch) {
        fluid_synth_set_gen(slot.synth, ch, GEN_FILTERFC, filter);
        fluid_synth_set_gen(slot.synth, ch, GEN_FILTERQ, sp.filterQ);
        fluid_synth_set_gen(slot.synth, ch, GEN_VOLENVDECAY, decay);
        fluid_synth_set_gen(slot.synth, ch, GEN_VOLENVRELEASE, release);
        fluid_synth_set_gen(slot.synth, ch, GEN_REVERBSEND, rsend * 1000.0f);
        fluid_synth_set_gen(slot.synth, ch, GEN_CHORUSSEND, csend * 1000.0f);
    }
    float room = sp.roomSize, damp = sp.damping, width = sp.reverbWidth, level = sp.reverbLevel;
    if (p.concertHall) {
        room += (0.93f - room) * 0.8f;
        damp *= 0.65f;
        width = 1.0f;
        level = clampf(level * 1.15f, 0.0f, 1.0f);
    }
    if (p.studio) room = clampf(room - 0.18f, 0.05f, 1.0f);
    fluid_synth_set_reverb_group_roomsize(slot.synth, -1, clampf(room, 0.0f, 1.0f));
    fluid_synth_set_reverb_group_damp(slot.synth, -1, clampf(damp, 0.0f, 1.0f));
    fluid_synth_set_reverb_group_width(slot.synth, -1, clampf(width, 0.0f, 1.0f) * 100.0f);
    fluid_synth_set_reverb_group_level(slot.synth, -1, clampf(level, 0.0f, 1.0f));
    fluid_synth_chorus_on(slot.synth, -1, csend > 0.0f ? 1 : 0);
    if (csend > 0.0f) {
        fluid_synth_set_chorus_group_nr(slot.synth, -1, clampi(sp.chorusVoices, 1, 10));
        fluid_synth_set_chorus_group_level(slot.synth, -1, sp.chorusLevel);
        fluid_synth_set_chorus_group_speed(slot.synth, -1, sp.chorusSpeedHz);
        fluid_synth_set_chorus_group_depth(slot.synth, -1, sp.chorusDepthMs);
    }
}

void Engine::updatePostChain(bool immediate) {
    const MasterParams &p = audioParams_;
    InstrumentSpec empty;
    const InstrumentSpec &sp = active_ >= 0 ? slots_[active_].spec : empty;
    tgtLow_ = sp.lowShelfDb + (p.studio ? 1.0f : 0.0f) + (p.soft ? 0.5f : 0.0f) + (p.concertHall ? 0.6f : 0.0f);
    tgtPres_ = sp.presenceDb + (p.studio ? 1.6f : 0.0f) + (p.soft ? -2.5f : 0.0f);
    tgtHigh_ = sp.highShelfDb + p.brightness * 5.0f + (p.studio ? 1.0f : 0.0f) + (p.soft ? -3.0f : 0.0f);
    compressor_.setAmount(p.studio ? 1.0f : 0.0f);
    width_.target = sp.stereoWidth * (p.concertHall ? 1.12f : 1.0f);
    const float v = clampf(p.volume, 0.0f, 1.0f);
    volume_.target = v * v * dbToGain(sp.outputGainDb);
    if (immediate) {
        curLow_ = tgtLow_;
        curPres_ = tgtPres_;
        curHigh_ = tgtHigh_;
        lowShelf_.configure(dsp::Biquad::LowShelf, float(sampleRate_), 160.0f, 0.7f, curLow_);
        presence_.configure(dsp::Biquad::Peak, float(sampleRate_), 2800.0f, 0.8f, curPres_);
        highShelf_.configure(dsp::Biquad::HighShelf, float(sampleRate_), 7500.0f, 0.7f, curHigh_);
    }
}

void Engine::releaseKey(int key) {
    const int idx = keySlot_[key];
    if (idx < 0) return;
    Slot &s = slots_[idx];
    const int layers = int(s.spec.layers.size());
    for (int ch = 0; ch < layers; ++ch) {
        const int k = key + s.spec.layers[ch].transpose;
        if (k >= 0 && k <= 127) fluid_synth_noteoff(s.synth, ch, k);
    }
    if (s.heldKeys > 0) s.heldKeys--;
    keySlot_[key] = -1;
}

void Engine::processEvent(const Event &e) {
    switch (e.type) {
        case Event::NoteOn: {
            if (active_ < 0) return;
            Slot &s = slots_[active_];
            if (s.state.load(std::memory_order_acquire) != Ready) return;
            const int key = e.a;
            if (keySlot_[key] >= 0) releaseKey(key);
            const int layers = int(s.spec.layers.size());
            for (int ch = 0; ch < layers; ++ch) {
                const LayerSpec &l = s.spec.layers[ch];
                const int k = key + l.transpose;
                if (k < 0 || k > 127) continue;
                const int v = clampi(int(std::lround(e.b * l.velocityScale)) + l.velocityOffset, 1, 127);
                fluid_synth_noteon(s.synth, ch, k, v);
            }
            keySlot_[key] = int8_t(active_);
            s.heldKeys++;
            break;
        }
        case Event::NoteOff:
            releaseKey(e.a);
            break;
        case Event::Sustain:
            pedal_ = e.a != 0;
            for (auto &s : slots_) {
                const int st = s.state.load(std::memory_order_acquire);
                if (st == Ready || st == Releasing) applySustain(s, pedal_);
            }
            break;
        case Event::AllNotesOff:
            for (auto &s : slots_) {
                const int st = s.state.load(std::memory_order_acquire);
                if (st == Ready || st == Releasing || st == Fading) {
                    for (int ch = 0; ch < kMaxLayers; ++ch) {
                        fluid_synth_cc(s.synth, ch, 64, 0);
                        fluid_synth_all_notes_off(s.synth, ch);
                    }
                    s.heldKeys = 0;
                }
            }
            keySlot_.fill(-1);
            pedal_ = false;
            break;
        case Event::Activate: {
            const int idx = e.a;
            if (idx == active_) break;
            if (active_ >= 0) {
                slots_[active_].state.store(Releasing, std::memory_order_release);
                slots_[active_].releasedAt = frameCounter_;
            }
            active_ = idx;
            applyVoicing(slots_[idx]);
            applySustain(slots_[idx], pedal_);
            updatePostChain(false);
            break;
        }
        case Event::ParamsChanged: {
            {
                std::unique_lock<std::mutex> lock(paramsMutex_, std::try_to_lock);
                if (!lock.owns_lock()) {
                    // A producer is writing right now; try again next block.
                    queue_.push({Event::ParamsChanged, 0, 0});
                    break;
                }
                audioParams_ = params_;
            }
            for (int i = 0; i < kSlots; ++i) {
                const int st = slots_[i].state.load(std::memory_order_acquire);
                if (st == Releasing || (st == Ready && i == active_)) applyVoicing(slots_[i]);
            }
            updatePostChain(false);
            break;
        }
        case Event::FadeOldest: {
            int victim = -1;
            for (int i = 0; i < kSlots; ++i) {
                if (slots_[i].state.load(std::memory_order_acquire) != Releasing) continue;
                if (victim < 0 || slots_[i].releasedAt < slots_[victim].releasedAt) victim = i;
            }
            if (victim >= 0) {
                slots_[victim].gainTarget = 0.0f;
                slots_[victim].state.store(Fading, std::memory_order_release);
            }
            break;
        }
    }
}

void Engine::render(float *out, int frames) {
    Event e;
    int budget = 512;
    while (budget-- > 0 && queue_.pop(e)) processEvent(e);

    const float rampStep = 1.0f / (0.03f * float(sampleRate_));
    int done = 0;
    float blockPeak = 0.0f;
    while (done < frames) {
        const int n = std::min(kBlock, frames - done);
        std::fill(mixL_.begin(), mixL_.begin() + n, 0.0f);
        std::fill(mixR_.begin(), mixR_.begin() + n, 0.0f);
        int voices = 0;

        for (int i = 0; i < kSlots; ++i) {
            Slot &s = slots_[i];
            const int st = s.state.load(std::memory_order_acquire);
            const bool audible = (st == Ready && i == active_) || st == Releasing || st == Fading;
            if (!audible) continue;
            fluid_synth_write_float(s.synth, n, tmpL_.data(), 0, 1, tmpR_.data(), 0, 1);
            float g = s.gain;
            for (int k = 0; k < n; ++k) {
                if (g != s.gainTarget) g += clampf(s.gainTarget - g, -rampStep, rampStep);
                mixL_[k] += tmpL_[k] * g;
                mixR_[k] += tmpR_[k] * g;
            }
            s.gain = g;
            const int sv = fluid_synth_get_active_voice_count(s.synth);
            voices += sv;

            if (st == Releasing && s.heldKeys == 0 && sv == 0) {
                applySustain(s, false);
                s.state.store(Idle, std::memory_order_release);
            } else if (st == Fading && s.gain <= 1e-4f) {
                fluid_synth_all_sounds_off(s.synth, -1);
                for (int ch = 0; ch < kMaxLayers; ++ch) fluid_synth_cc(s.synth, ch, 64, 0);
                for (auto &ks : keySlot_)
                    if (ks == i) ks = -1;
                s.heldKeys = 0;
                s.state.store(Idle, std::memory_order_release);
            }
        }

        // Smoothly follow EQ targets (max 0.3 dB per block avoids zipper noise).
        auto follow = [](float &cur, float tgt) {
            if (std::fabs(tgt - cur) < 0.01f) { bool changed = cur != tgt; cur = tgt; return changed; }
            cur += clampf(tgt - cur, -0.3f, 0.3f);
            return true;
        };
        if (follow(curLow_, tgtLow_))
            lowShelf_.configure(dsp::Biquad::LowShelf, float(sampleRate_), 160.0f, 0.7f, curLow_);
        if (follow(curPres_, tgtPres_))
            presence_.configure(dsp::Biquad::Peak, float(sampleRate_), 2800.0f, 0.8f, curPres_);
        if (follow(curHigh_, tgtHigh_))
            highShelf_.configure(dsp::Biquad::HighShelf, float(sampleRate_), 7500.0f, 0.7f, curHigh_);

        float *o = out + size_t(done) * 2;
        for (int k = 0; k < n; ++k) {
            float l = mixL_[k], r = mixR_[k];
            lowShelf_.process(l, r);
            presence_.process(l, r);
            highShelf_.process(l, r);
            const float w = width_.next();
            const float mid = 0.5f * (l + r), side = 0.5f * (l - r) * w;
            l = mid + side;
            r = mid - side;
            compressor_.process(l, r);
            const float v = volume_.next();
            l *= v;
            r *= v;
            limiter_.process(l, r);
            o[2 * k] = l;
            o[2 * k + 1] = r;
            blockPeak = std::max(blockPeak, std::max(std::fabs(l), std::fabs(r)));
        }
        activeVoices_.store(voices, std::memory_order_relaxed);
        done += n;
    }
    frameCounter_ += uint64_t(frames);
    peak_.store(blockPeak, std::memory_order_relaxed);
}

} // namespace nocturne

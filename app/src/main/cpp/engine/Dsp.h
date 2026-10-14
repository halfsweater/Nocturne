#pragma once

#include <algorithm>
#include <cmath>
#include <vector>

namespace nocturne::dsp {

/** RBJ cookbook biquad, transposed direct form II, stereo. */
class Biquad {
public:
    enum Kind { LowShelf, Peak, HighShelf };

    void configure(Kind kind, float sampleRate, float freq, float q, float gainDb) {
        const double A = std::pow(10.0, gainDb / 40.0);
        const double w0 = 2.0 * M_PI * freq / sampleRate;
        const double cw = std::cos(w0), sw = std::sin(w0);
        const double alpha = sw / (2.0 * q);
        double b0, b1, b2, a0, a1, a2;
        switch (kind) {
            case Peak:
                b0 = 1 + alpha * A; b1 = -2 * cw; b2 = 1 - alpha * A;
                a0 = 1 + alpha / A; a1 = -2 * cw; a2 = 1 - alpha / A;
                break;
            case LowShelf: {
                const double s = 2 * std::sqrt(A) * alpha;
                b0 = A * ((A + 1) - (A - 1) * cw + s);
                b1 = 2 * A * ((A - 1) - (A + 1) * cw);
                b2 = A * ((A + 1) - (A - 1) * cw - s);
                a0 = (A + 1) + (A - 1) * cw + s;
                a1 = -2 * ((A - 1) + (A + 1) * cw);
                a2 = (A + 1) + (A - 1) * cw - s;
                break;
            }
            case HighShelf:
            default: {
                const double s = 2 * std::sqrt(A) * alpha;
                b0 = A * ((A + 1) + (A - 1) * cw + s);
                b1 = -2 * A * ((A - 1) + (A + 1) * cw);
                b2 = A * ((A + 1) + (A - 1) * cw - s);
                a0 = (A + 1) - (A - 1) * cw + s;
                a1 = 2 * ((A - 1) - (A + 1) * cw);
                a2 = (A + 1) - (A - 1) * cw - s;
                break;
            }
        }
        b0_ = float(b0 / a0); b1_ = float(b1 / a0); b2_ = float(b2 / a0);
        a1_ = float(a1 / a0); a2_ = float(a2 / a0);
        bypass_ = std::fabs(gainDb) < 0.01f;
    }

    inline void process(float &l, float &r) {
        if (bypass_) {
            // keep state decaying so re-enabling does not click
            z1l_ *= 0.5f; z2l_ *= 0.5f; z1r_ *= 0.5f; z2r_ *= 0.5f;
            return;
        }
        const float yl = b0_ * l + z1l_;
        z1l_ = b1_ * l - a1_ * yl + z2l_;
        z2l_ = b2_ * l - a2_ * yl;
        const float yr = b0_ * r + z1r_;
        z1r_ = b1_ * r - a1_ * yr + z2r_;
        z2r_ = b2_ * r - a2_ * yr;
        l = yl;
        r = yr;
    }

    void reset() { z1l_ = z2l_ = z1r_ = z2r_ = 0; }

private:
    float b0_ = 1, b1_ = 0, b2_ = 0, a1_ = 0, a2_ = 0;
    float z1l_ = 0, z2l_ = 0, z1r_ = 0, z2r_ = 0;
    bool bypass_ = true;
};

/** Gentle feed-forward bus compressor used by the "Studio" sound. */
class Compressor {
public:
    void setSampleRate(float sr) {
        attack_ = 1.0f - std::exp(-1.0f / (0.012f * sr));
        release_ = 1.0f - std::exp(-1.0f / (0.150f * sr));
        mixStep_ = 1.0f / (0.05f * sr);
    }
    void setAmount(float a) { mixTarget_ = std::clamp(a, 0.0f, 1.0f); }

    inline void process(float &l, float &r) {
        if (mix_ != mixTarget_) {
            mix_ += std::clamp(mixTarget_ - mix_, -mixStep_, mixStep_);
        }
        if (mix_ <= 0.0f) { env_ = 0; return; }
        const float x = std::max(std::fabs(l), std::fabs(r));
        env_ += (x > env_ ? attack_ : release_) * (x - env_);
        float g = 1.0f;
        if (env_ > threshold_) {
            const float overDb = 20.0f * std::log10(env_ / threshold_);
            g = std::pow(10.0f, -(overDb * (1.0f - 1.0f / ratio_)) / 20.0f);
        }
        const float wet = g * makeup_;
        const float gain = 1.0f + mix_ * (wet - 1.0f);
        l *= gain;
        r *= gain;
    }

private:
    float env_ = 0, attack_ = 0.01f, release_ = 0.001f;
    float threshold_ = 0.12f, ratio_ = 2.6f, makeup_ = 1.45f;
    float mix_ = 0, mixTarget_ = 0, mixStep_ = 0.001f;
};

/** Look-ahead peak limiter with a soft safety clip. Adds `lookahead` samples of latency. */
class Limiter {
public:
    void setSampleRate(float sr, int lookahead = 64) {
        look_ = lookahead;
        delayL_.assign(look_, 0.0f);
        delayR_.assign(look_, 0.0f);
        window_.assign(look_, 1.0f);
        pos_ = 0;
        attack_ = 1.0f - std::exp(-4.6f / float(look_));
        release_ = 1.0f - std::exp(-1.0f / (0.09f * sr));
    }

    inline void process(float &l, float &r) {
        const float peak = std::max(std::fabs(l), std::fabs(r));
        const float want = peak > threshold_ ? threshold_ / peak : 1.0f;
        window_[pos_] = want;
        float minGain = 1.0f;
        for (int i = 0; i < look_; ++i) minGain = std::min(minGain, window_[i]);
        gain_ += (minGain < gain_ ? attack_ : release_) * (minGain - gain_);
        const float dl = delayL_[pos_], dr = delayR_[pos_];
        delayL_[pos_] = l;
        delayR_[pos_] = r;
        pos_ = (pos_ + 1) % look_;
        l = softClip(dl * gain_);
        r = softClip(dr * gain_);
    }

    float gainReduction() const { return gain_; }

private:
    static inline float softClip(float x) {
        const float a = std::fabs(x);
        if (a <= 0.95f) return x;
        const float y = 0.95f + 0.05f * std::tanh((a - 0.95f) / 0.05f);
        return x < 0 ? -y : y;
    }

    std::vector<float> delayL_, delayR_, window_;
    int look_ = 64, pos_ = 0;
    float gain_ = 1.0f, attack_ = 0.1f, release_ = 0.0005f;
    float threshold_ = 0.89f;
};

/** One-pole parameter smoother. */
struct Smoothed {
    float value = 0, target = 0, coef = 0.001f;
    void setTimeConstant(float sr, float seconds) { coef = 1.0f - std::exp(-1.0f / (seconds * sr)); }
    inline float next() { value += coef * (target - value); return value; }
};

} // namespace nocturne::dsp

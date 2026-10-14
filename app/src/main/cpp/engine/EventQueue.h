#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>

namespace nocturne {

struct Event {
    enum Type : uint8_t { NoteOn, NoteOff, Sustain, AllNotesOff, Activate, ParamsChanged, FadeOldest };
    Type type;
    int16_t a;
    int16_t b;
};

/**
 * Bounded multi-producer / single-consumer queue. Producers (UI thread, loader
 * thread) serialise among themselves with a mutex; the audio thread consumes
 * wait-free and never touches the mutex.
 */
template <size_t Capacity>
class EventQueue {
    static_assert((Capacity & (Capacity - 1)) == 0, "capacity must be a power of two");

public:
    bool push(const Event &e) {
        std::lock_guard<std::mutex> lock(producerMutex_);
        const uint32_t w = write_.load(std::memory_order_relaxed);
        const uint32_t r = read_.load(std::memory_order_acquire);
        if (w - r >= Capacity) return false;
        buffer_[w & (Capacity - 1)] = e;
        write_.store(w + 1, std::memory_order_release);
        return true;
    }

    bool pop(Event &e) {
        const uint32_t r = read_.load(std::memory_order_relaxed);
        const uint32_t w = write_.load(std::memory_order_acquire);
        if (r == w) return false;
        e = buffer_[r & (Capacity - 1)];
        read_.store(r + 1, std::memory_order_release);
        return true;
    }

private:
    Event buffer_[Capacity];
    std::atomic<uint32_t> write_{0};
    std::atomic<uint32_t> read_{0};
    std::mutex producerMutex_;
};

} // namespace nocturne

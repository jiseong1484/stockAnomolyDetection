#pragma once

#include <atomic>
#include <array>
#include <cassert>
#include <cstddef>
#include <optional>

namespace trading {

// Single-Producer Single-Consumer lock-free ring buffer.
// Used on the 단타(scalping) hot path — one Kafka consumer thread pushes
// ticks, one aggregation thread drains.
// Capacity must be a power of two.
template <typename T, std::size_t Capacity>
class SPSCQueue {
    static_assert(Capacity > 1 && (Capacity & (Capacity - 1)) == 0,
                  "Capacity must be a power of two");

public:
    // Producer side — returns false if full.
    bool push(const T& item) noexcept {
        const std::size_t head = head_.load(std::memory_order_relaxed);
        const std::size_t next = (head + 1) & kMask;
        if (next == tail_.load(std::memory_order_acquire)) return false; // full
        buffer_[head] = item;
        head_.store(next, std::memory_order_release);
        return true;
    }

    bool push(T&& item) noexcept {
        const std::size_t head = head_.load(std::memory_order_relaxed);
        const std::size_t next = (head + 1) & kMask;
        if (next == tail_.load(std::memory_order_acquire)) return false;
        buffer_[head] = std::move(item);
        head_.store(next, std::memory_order_release);
        return true;
    }

    // Consumer side — returns nullopt if empty.
    std::optional<T> pop() noexcept {
        const std::size_t tail = tail_.load(std::memory_order_relaxed);
        if (tail == head_.load(std::memory_order_acquire)) return std::nullopt;
        T item = std::move(buffer_[tail]);
        tail_.store((tail + 1) & kMask, std::memory_order_release);
        return item;
    }

    bool empty() const noexcept {
        return head_.load(std::memory_order_acquire) ==
               tail_.load(std::memory_order_acquire);
    }

    std::size_t size() const noexcept {
        const std::size_t h = head_.load(std::memory_order_acquire);
        const std::size_t t = tail_.load(std::memory_order_acquire);
        return (h - t) & kMask;
    }

    static constexpr std::size_t capacity() noexcept { return Capacity - 1; }

private:
    static constexpr std::size_t kMask = Capacity - 1;

    alignas(64) std::atomic<std::size_t> head_{0};
    alignas(64) std::atomic<std::size_t> tail_{0};
    alignas(64) std::array<T, Capacity>  buffer_{};
};


// Multi-Producer Single-Consumer lock-free queue using a fixed-size ring buffer
// with per-slot sequencing. Used when multiple Kafka partition threads push
// ticks into a single aggregation consumer.
// Capacity must be a power of two.
template <typename T, std::size_t Capacity>
class MPSCQueue {
    static_assert(Capacity > 1 && (Capacity & (Capacity - 1)) == 0,
                  "Capacity must be a power of two");

    struct Slot {
        alignas(64) std::atomic<std::size_t> sequence{0};
        T data{};
    };

public:
    MPSCQueue() {
        for (std::size_t i = 0; i < Capacity; ++i)
            slots_[i].sequence.store(i, std::memory_order_relaxed);
        enqueue_pos_.store(0, std::memory_order_relaxed);
        dequeue_pos_.store(0, std::memory_order_relaxed);
    }

    // Producer side — spin-waits briefly; returns false if definitely full.
    bool push(const T& data) noexcept {
        std::size_t pos = enqueue_pos_.load(std::memory_order_relaxed);
        for (;;) {
            Slot& slot = slots_[pos & kMask];
            const std::size_t seq = slot.sequence.load(std::memory_order_acquire);
            const std::intptr_t diff = static_cast<std::intptr_t>(seq) -
                                       static_cast<std::intptr_t>(pos);
            if (diff == 0) {
                if (enqueue_pos_.compare_exchange_weak(pos, pos + 1,
                                                       std::memory_order_relaxed))
                {
                    slot.data = data;
                    slot.sequence.store(pos + 1, std::memory_order_release);
                    return true;
                }
            } else if (diff < 0) {
                return false; // full
            } else {
                pos = enqueue_pos_.load(std::memory_order_relaxed);
            }
        }
    }

    // Consumer side.
    std::optional<T> pop() noexcept {
        const std::size_t pos = dequeue_pos_.load(std::memory_order_relaxed);
        Slot& slot = slots_[pos & kMask];
        const std::size_t seq = slot.sequence.load(std::memory_order_acquire);
        const std::intptr_t diff = static_cast<std::intptr_t>(seq) -
                                   static_cast<std::intptr_t>(pos + 1);
        if (diff != 0) return std::nullopt; // empty
        T item = std::move(slot.data);
        slot.sequence.store(pos + Capacity, std::memory_order_release);
        dequeue_pos_.store(pos + 1, std::memory_order_relaxed);
        return item;
    }

    bool empty() const noexcept {
        const std::size_t pos = dequeue_pos_.load(std::memory_order_acquire);
        const Slot& slot = slots_[pos & kMask];
        const std::size_t seq = slot.sequence.load(std::memory_order_acquire);
        return static_cast<std::intptr_t>(seq) -
               static_cast<std::intptr_t>(pos + 1) != 0;
    }

    static constexpr std::size_t capacity() noexcept { return Capacity; }

private:
    static constexpr std::size_t kMask = Capacity - 1;

    alignas(64) std::array<Slot, Capacity> slots_;
    alignas(64) std::atomic<std::size_t>   enqueue_pos_{0};
    alignas(64) std::atomic<std::size_t>   dequeue_pos_{0};
};

} // namespace trading

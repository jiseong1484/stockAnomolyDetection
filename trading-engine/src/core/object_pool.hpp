#pragma once

#include <array>
#include <atomic>
#include <cassert>
#include <cstddef>
#include <memory>
#include <new>
#include <type_traits>

namespace trading {

// Fixed-size, thread-safe object pool using a lock-free free-list.
// T must be default-constructible. PoolSize must be a power of two.
template <typename T, std::size_t PoolSize>
class ObjectPool {
    static_assert(PoolSize > 0 && (PoolSize & (PoolSize - 1)) == 0,
                  "PoolSize must be a power of two");

public:
    ObjectPool() {
        for (std::size_t i = 0; i < PoolSize; ++i) {
            free_list_[i].next.store(i + 1, std::memory_order_relaxed);
        }
        head_.store(0, std::memory_order_relaxed);
    }

    // Returns a pointer to a zeroed slot, or nullptr if pool is exhausted.
    T* acquire() noexcept {
        std::size_t idx = head_.load(std::memory_order_acquire);
        while (true) {
            if (idx == kNull) return nullptr;
            const std::size_t next = free_list_[idx].next.load(std::memory_order_relaxed);
            if (head_.compare_exchange_weak(idx, next,
                                            std::memory_order_release,
                                            std::memory_order_acquire)) {
                return reinterpret_cast<T*>(&slots_[idx]);
            }
        }
    }

    // Calls destructor and returns slot to the free-list.
    void release(T* ptr) noexcept {
        if (!ptr) return;
        ptr->~T();
        const std::size_t idx = slot_index(ptr);
        std::size_t current = head_.load(std::memory_order_acquire);
        while (true) {
            free_list_[idx].next.store(current, std::memory_order_relaxed);
            if (head_.compare_exchange_weak(current, idx,
                                            std::memory_order_release,
                                            std::memory_order_acquire)) {
                return;
            }
        }
    }

    static constexpr std::size_t capacity() noexcept { return PoolSize; }

private:
    static constexpr std::size_t kNull = PoolSize;

    std::size_t slot_index(const T* ptr) const noexcept {
        const auto* base = reinterpret_cast<const StorageSlot*>(slots_.data());
        const auto* slot = reinterpret_cast<const StorageSlot*>(ptr);
        const std::size_t idx = static_cast<std::size_t>(slot - base);
        assert(idx < PoolSize);
        return idx;
    }

    struct alignas(T) StorageSlot {
        std::byte data[sizeof(T)];
    };

    struct FreeNode {
        std::atomic<std::size_t> next{kNull};
    };

    // Separate storage and free-list to avoid aliasing between live objects
    // and the next-pointer metadata.
    alignas(64) std::array<StorageSlot, PoolSize> slots_;
    alignas(64) std::array<FreeNode, PoolSize>    free_list_;
    alignas(64) std::atomic<std::size_t>          head_{0};
};

// RAII handle that auto-releases back to the pool on destruction.
template <typename T, std::size_t PoolSize>
class PoolHandle {
public:
    PoolHandle() = default;
    PoolHandle(ObjectPool<T, PoolSize>& pool, T* ptr)
        : pool_(&pool), ptr_(ptr) {}

    PoolHandle(const PoolHandle&)            = delete;
    PoolHandle& operator=(const PoolHandle&) = delete;

    PoolHandle(PoolHandle&& other) noexcept
        : pool_(other.pool_), ptr_(other.ptr_) {
        other.pool_ = nullptr;
        other.ptr_  = nullptr;
    }

    PoolHandle& operator=(PoolHandle&& other) noexcept {
        if (this != &other) {
            reset();
            pool_       = other.pool_;
            ptr_        = other.ptr_;
            other.pool_ = nullptr;
            other.ptr_  = nullptr;
        }
        return *this;
    }

    ~PoolHandle() { reset(); }

    T* get() const noexcept { return ptr_; }
    T* operator->() const noexcept { return ptr_; }
    T& operator*() const noexcept { return *ptr_; }
    explicit operator bool() const noexcept { return ptr_ != nullptr; }

    void reset() noexcept {
        if (ptr_ && pool_) {
            pool_->release(ptr_);
            ptr_  = nullptr;
            pool_ = nullptr;
        }
    }

private:
    ObjectPool<T, PoolSize>* pool_{nullptr};
    T*                       ptr_{nullptr};
};

} // namespace trading

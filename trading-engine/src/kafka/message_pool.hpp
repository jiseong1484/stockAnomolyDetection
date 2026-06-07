#pragma once

#include "../core/object_pool.hpp"

#include <array>
#include <cstddef>
#include <cstring>

namespace trading {

inline constexpr std::size_t kPayloadCapacity = 4096; // 4 KB per message buffer (sufficient for JSON payloads)

// Pre-allocated message buffer. Avoids heap allocation on the Kafka hot path.
// Acquire from MessagePool, fill payload, send, then release back.
struct MessageBuffer {
    std::array<char, kPayloadCapacity> payload{};
    std::size_t                        payload_size{0};
    char                               topic[64]{};
    char                               key[32]{};
    std::size_t                        key_size{0};

    void reset() noexcept {
        payload_size = 0;
        key_size     = 0;
    }

    bool write(const void* data, std::size_t size) noexcept {
        if (size > kPayloadCapacity) return false;
        std::memcpy(payload.data(), data, size);
        payload_size = size;
        return true;
    }
};

// Pool of 128 MessageBuffers — enough for burst windows with one buffer
// per in-flight Kafka produce request.
using MessagePool = ObjectPool<MessageBuffer, 128>;
using MessageHandle = PoolHandle<MessageBuffer, 128>;

} // namespace trading

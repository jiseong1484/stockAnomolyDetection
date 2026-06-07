#pragma once

#include "event_types.hpp"

#include <functional>
#include <mutex>
#include <shared_mutex>
#include <thread>
#include <vector>
#include <queue>
#include <condition_variable>
#include <variant>

namespace trading {

// ─── EventBus ────────────────────────────────────────────────────────────────
// Internal pub/sub bus for trading events.
// Each event type has its own queue + dedicated dispatch thread so that a slow
// SwingHandler never blocks the ScalpingHandler.
//
// Usage:
//   bus.subscribe<ScalpingEvent>(handler_fn);
//   bus.publish(ScalpingEvent{...});

using TradingEvent = std::variant<ScalpingEvent, SwingEvent>;

template <typename EventT>
using EventHandler = std::function<void(const EventT&)>;

class EventBus {
public:
    EventBus() {
        scalping_thread_ = std::thread(&EventBus::dispatch_loop<ScalpingEvent>,
                                       this,
                                       std::ref(scalping_queue_),
                                       std::ref(scalping_handlers_),
                                       std::ref(scalping_cv_),
                                       std::ref(scalping_mtx_));

        swing_thread_ = std::thread(&EventBus::dispatch_loop<SwingEvent>,
                                    this,
                                    std::ref(swing_queue_),
                                    std::ref(swing_handlers_),
                                    std::ref(swing_cv_),
                                    std::ref(swing_mtx_));
    }

    ~EventBus() {
        running_.store(false, std::memory_order_release);
        scalping_cv_.notify_all();
        swing_cv_.notify_all();
        if (scalping_thread_.joinable()) scalping_thread_.join();
        if (swing_thread_.joinable())    swing_thread_.join();
    }

    // ── Subscribe ─────────────────────────────────────────────────────────────
    void subscribe(EventHandler<ScalpingEvent> h) {
        std::unique_lock lock(scalping_handler_mtx_);
        scalping_handlers_.push_back(std::move(h));
    }

    void subscribe(EventHandler<SwingEvent> h) {
        std::unique_lock lock(swing_handler_mtx_);
        swing_handlers_.push_back(std::move(h));
    }

    // ── Publish ───────────────────────────────────────────────────────────────
    void publish(ScalpingEvent event) {
        {
            std::unique_lock lock(scalping_mtx_);
            scalping_queue_.push(std::move(event));
        }
        scalping_cv_.notify_one();
    }

    void publish(SwingEvent event) {
        {
            std::unique_lock lock(swing_mtx_);
            swing_queue_.push(std::move(event));
        }
        swing_cv_.notify_one();
    }

private:
    // Generic dispatch loop parameterised on event type.
    template <typename EventT>
    void dispatch_loop(std::queue<EventT>&               queue,
                       std::vector<EventHandler<EventT>>& handlers,
                       std::condition_variable&           cv,
                       std::mutex&                        q_mtx)
    {
        while (running_.load(std::memory_order_acquire)) {
            std::unique_lock lock(q_mtx);
            cv.wait(lock, [&] {
                return !queue.empty() || !running_.load(std::memory_order_acquire);
            });

            while (!queue.empty()) {
                EventT event = std::move(queue.front());
                queue.pop();
                lock.unlock();

                // Call handlers outside the lock.
                for (auto& handler : handlers) {
                    handler(event);
                }
                lock.lock();
            }
        }
    }

    std::atomic<bool> running_{true};

    // Scalping dispatch
    std::queue<ScalpingEvent>               scalping_queue_;
    std::vector<EventHandler<ScalpingEvent>> scalping_handlers_;
    std::mutex                              scalping_mtx_;
    std::shared_mutex                       scalping_handler_mtx_;
    std::condition_variable                 scalping_cv_;
    std::thread                             scalping_thread_;

    // Swing dispatch
    std::queue<SwingEvent>               swing_queue_;
    std::vector<EventHandler<SwingEvent>> swing_handlers_;
    std::mutex                           swing_mtx_;
    std::shared_mutex                    swing_handler_mtx_;
    std::condition_variable              swing_cv_;
    std::thread                          swing_thread_;
};

} // namespace trading

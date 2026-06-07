#pragma once

#include "message_pool.hpp"

#include <librdkafka/rdkafkacpp.h>

#include <memory>
#include <stdexcept>
#include <string>

namespace trading {

// Delivery-report callback — logs failures; no retries (fire-and-forget for
// urgent signals; for candle messages the window close guarantees ordering).
class DeliveryReportCb final : public RdKafka::DeliveryReportCb {
public:
    void dr_cb(RdKafka::Message& message) override {
        if (message.err() != RdKafka::ERR_NO_ERROR) {
            // In production: wire to structured logger / metrics counter.
            fprintf(stderr, "[kafka] delivery failed: %s  topic=%s\n",
                    message.errstr().c_str(),
                    message.topic_name().c_str());
        }
    }
};

// Thin wrapper around librdkafka producer.
// Owns a MessagePool for zero-malloc serialization on the hot path.
class KafkaProducer {
public:
    KafkaProducer(const std::string& brokers,
                  const std::string& client_id) {
        std::string err;
        auto* conf = RdKafka::Conf::create(RdKafka::Conf::CONF_GLOBAL);

        auto set = [&](const char* key, const std::string& val) {
            if (conf->set(key, val, err) != RdKafka::Conf::CONF_OK)
                throw std::runtime_error(std::string("Kafka conf: ") + err);
        };

        set("bootstrap.servers",  brokers);
        set("client.id",          client_id);
        set("acks",               "1");        // leader ack only — latency vs. durability tradeoff
        set("linger.ms",          "0");        // no batching delay on urgent-signals
        set("queue.buffering.max.messages", "100000");
        conf->set("dr_cb", &dr_cb_, err);

        producer_.reset(RdKafka::Producer::create(conf, err));
        if (!producer_) {
            delete conf;  // create() frees conf only on success; free it on failure
            throw std::runtime_error("Failed to create Kafka producer: " + err);
        }
        // conf is owned and freed by the producer on success — do NOT delete here
    }

    ~KafkaProducer() {
        producer_->flush(1000 /* ms */);  // 1s timeout — avoids long hang when Kafka is down
    }

    // Publish a pre-serialized MessageBuffer.
    // The buffer is released back to the pool after the call.
    bool publish(MessageHandle handle) {
        if (!handle) return false;

        const RdKafka::ErrorCode err = producer_->produce(
            handle->topic,
            RdKafka::Topic::PARTITION_UA,          // auto partition
            RdKafka::Producer::RK_MSG_COPY,        // copy payload — buffer returns to pool
            const_cast<char*>(handle->payload.data()),
            handle->payload_size,
            handle->key_size > 0 ? handle->key : nullptr,
            handle->key_size,
            /* timestamp */ 0,
            /* opaque */    nullptr
        );

        // handle goes out of scope here — releases buffer back to pool.
        if (err != RdKafka::ERR_NO_ERROR) {
            fprintf(stderr, "[kafka] produce error: %s\n",
                    RdKafka::err2str(err).c_str());
            return false;
        }

        // Non-blocking poll to trigger delivery callbacks.
        producer_->poll(0);
        return true;
    }

    // Acquire a MessageBuffer from the pool.
    // Caller fills the buffer and passes the handle to publish().
    MessageHandle acquire_buffer() {
        MessageBuffer* buf = pool_.acquire();
        if (!buf) return {};
        buf->reset();
        return MessageHandle(pool_, buf);
    }

    MessagePool& pool() noexcept { return pool_; }

private:
    DeliveryReportCb                   dr_cb_;
    std::unique_ptr<RdKafka::Producer> producer_;
    MessagePool                        pool_;
};

} // namespace trading

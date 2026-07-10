# --- Build stage -----------------------------------------------------------
FROM debian:bookworm-slim AS build

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential cmake git ca-certificates librdkafka-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY CMakeLists.txt ./
COPY src ./src
COPY proto ./proto
COPY tests ./tests

RUN cmake -B build -DCMAKE_BUILD_TYPE=Release \
    && cmake --build build -j"$(nproc)" --target trading_engine

# --- Runtime stage -----------------------------------------------------------
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        librdkafka1 librdkafka++1 ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -s /usr/sbin/nologin engine

WORKDIR /app
COPY --from=build /src/build/trading_engine ./trading_engine
USER engine

ENTRYPOINT ["./trading_engine"]

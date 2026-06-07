cmake_minimum_required(VERSION 3.20)
project(trading_engine CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Compile flags ──────────────────────────────────────────────────────────────
add_compile_options(
    -O3
    -march=native
    -Wall
    -Wextra
    -Wpedantic
)

# ── Dependencies ───────────────────────────────────────────────────────────────

# librdkafka (brew install librdkafka) — located directly to avoid pkg-config requirement
find_library(RDKAFKA_CPP_LIB  NAMES rdkafka++  HINTS /opt/homebrew/lib REQUIRED)
find_library(RDKAFKA_C_LIB    NAMES rdkafka    HINTS /opt/homebrew/lib REQUIRED)
find_path(RDKAFKA_INCLUDE_DIR NAMES librdkafka/rdkafkacpp.h
          HINTS /opt/homebrew/include REQUIRED)

# nlohmann/json — header-only JSON library
include(FetchContent)
FetchContent_Declare(
    nlohmann_json
    GIT_REPOSITORY https://github.com/nlohmann/json.git
    GIT_TAG        v3.11.3
)
set(JSON_BuildTests OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(nlohmann_json)

# ── Sources ────────────────────────────────────────────────────────────────────
file(GLOB_RECURSE ENGINE_SRCS
    "${CMAKE_SOURCE_DIR}/src/*.cpp"
)

add_executable(trading_engine
    ${ENGINE_SRCS}
)

target_include_directories(trading_engine PRIVATE
    ${CMAKE_SOURCE_DIR}/src
    ${RDKAFKA_INCLUDE_DIR}
)

target_link_libraries(trading_engine PRIVATE
    ${RDKAFKA_CPP_LIB}
    ${RDKAFKA_C_LIB}
    nlohmann_json::nlohmann_json
    pthread
)

# ── Sanitizers (debug builds) ──────────────────────────────────────────────────
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(trading_engine PRIVATE -fsanitize=address,undefined)
    target_link_options(trading_engine PRIVATE    -fsanitize=address,undefined)
endif()

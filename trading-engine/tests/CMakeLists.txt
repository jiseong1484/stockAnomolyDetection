# googletest — fetched the same way nlohmann_json is fetched in the top-level CMakeLists.
include(FetchContent)
FetchContent_Declare(
    googletest
    GIT_REPOSITORY https://github.com/google/googletest.git
    GIT_TAG        v1.14.0
)
set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

add_executable(trading_engine_tests
    test_zscore_calculator.cpp
)

target_include_directories(trading_engine_tests PRIVATE ${CMAKE_SOURCE_DIR}/src)
target_link_libraries(trading_engine_tests PRIVATE gtest_main)
target_compile_options(trading_engine_tests PRIVATE -Wall -Wextra)

include(GoogleTest)
gtest_discover_tests(trading_engine_tests)

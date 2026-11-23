add_library(spectrum_core STATIC
    src/analysis.cpp
    src/pcm_decode.cpp
    src/decimator.cpp
    src/real_fft.cpp
    src/band_mapper.cpp
)

target_include_directories(spectrum_core PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
target_compile_features(spectrum_core PUBLIC cxx_std_20)
target_compile_options(spectrum_core PRIVATE -Wall -Wextra -Wformat=2)
set_target_properties(spectrum_core PROPERTIES
    CXX_EXTENSIONS OFF
    POSITION_INDEPENDENT_CODE ON
)

option(SPECTRUM_EXPERIMENTAL_PACKED24_LD3 "Use ARM64 LD3 for packed PCM decode" ON)
option(SPECTRUM_DEFAULT_FLOAT32_DSP "Use Float32 DSP by default (OFF selects LegacyDouble)" ON)
foreach(feature IN ITEMS SPECTRUM_EXPERIMENTAL_PACKED24_LD3 SPECTRUM_DEFAULT_FLOAT32_DSP)
    if(${feature})
        target_compile_definitions(spectrum_core PRIVATE ${feature}=1)
    endif()
endforeach()

option(SPECTRUM_BUILD_CORE_HARNESS "Build the standalone core benchmark and correctness executable" OFF)
if(SPECTRUM_BUILD_CORE_HARNESS)
    add_executable(spectrum_core_bench tests/core_bench.cpp)
    add_executable(spectrum_core_checks tests/core_checks.cpp)
    foreach(target IN ITEMS spectrum_core_bench spectrum_core_checks)
        target_link_libraries(${target} PRIVATE spectrum_core)
        target_include_directories(${target} PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
        target_compile_options(${target} PRIVATE -Wall -Wextra -Wformat=2)
        target_compile_definitions(${target} PRIVATE SPECTRUM_HARNESS_HAS_DSP_BACKEND=1)
        set_target_properties(${target} PROPERTIES CXX_EXTENSIONS OFF)
    endforeach()
endif()

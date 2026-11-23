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

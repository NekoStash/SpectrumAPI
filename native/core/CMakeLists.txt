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

cmake_minimum_required(VERSION 3.22)
project(SpectrumAPI VERSION 0.2.0 LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
get_filename_component(SPECTRUM_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/.." ABSOLUTE)

if(NOT ANDROID)
    add_subdirectory(core)
    return()
endif()
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "SpectrumAPI native module supports arm64-v8a only")
endif()
if(NOT ANDROID_STL STREQUAL "c++_static")
    message(FATAL_ERROR "SpectrumAPI requires ANDROID_STL=c++_static so single-FD Zygisk Next injection never opens /data/adb dependencies from audioserver")
endif()

function(spectrum_optimize target)
    target_compile_options(${target} PRIVATE -O3 -Wall -Wextra -Wformat=2
        -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections
        -fdata-sections -mbranch-protection=standard)
    target_link_options(${target} PRIVATE -Wl,-z,defs -Wl,--gc-sections
        -Wl,--exclude-libs,ALL -Wl,-s)
endfunction()

set(AIDL_ROOT "${SPECTRUM_ROOT}/build/generated/aidl")
file(GLOB_RECURSE AIDL_SOURCES CONFIGURE_DEPENDS "${AIDL_ROOT}/src/*.cpp")
if(NOT AIDL_SOURCES)
    message(FATAL_ERROR "Generate bindings first: python tools/generate_aidl.py")
endif()
if(NOT SPECTRUM_SDK_ROOT)
    get_filename_component(_ndk_parent "${CMAKE_ANDROID_NDK}" DIRECTORY)
    get_filename_component(SPECTRUM_SDK_ROOT "${_ndk_parent}" DIRECTORY)
endif()
set(NDK_HELPERS "${SPECTRUM_SDK_ROOT}/platforms/android-37.0/optional/libbinder_ndk_cpp")
if(NOT EXISTS "${NDK_HELPERS}/android/binder_interface_utils.h")
    message(FATAL_ERROR "Android 37 platform optional libbinder_ndk_cpp headers are required")
endif()

add_library(spectrum_aidl STATIC ${AIDL_SOURCES})
target_include_directories(spectrum_aidl PUBLIC "${AIDL_ROOT}/include" "${NDK_HELPERS}")
target_link_libraries(spectrum_aidl PUBLIC binder_ndk)
target_compile_options(spectrum_aidl PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)

add_subdirectory(core)

set(SPECTRUM_PLATFORM_LINK_MODE "stubs" CACHE STRING "Platform link inputs: stubs or external")
set_property(CACHE SPECTRUM_PLATFORM_LINK_MODE PROPERTY STRINGS stubs external)
set(SPECTRUM_PLATFORM_LINK_DIR "" CACHE PATH "Explicit link-only platform library directory")
if(SPECTRUM_PLATFORM_LINK_MODE STREQUAL "external")
    if(NOT IS_DIRECTORY "${SPECTRUM_PLATFORM_LINK_DIR}")
        message(FATAL_ERROR "external mode requires an explicit SPECTRUM_PLATFORM_LINK_DIR")
    endif()
    foreach(_lib binder utils binder_ndk)
        set(_input "${SPECTRUM_PLATFORM_LINK_DIR}/lib${_lib}.so")
        if(NOT EXISTS "${_input}" OR IS_SYMLINK "${_input}")
            message(FATAL_ERROR "Required regular platform link input missing: ${_input}")
        endif()
        add_library(rom_${_lib} SHARED IMPORTED)
        set_target_properties(rom_${_lib} PROPERTIES IMPORTED_LOCATION "${_input}")
    endforeach()
    set(SPECTRUM_PLATFORM_LINK_TARGETS rom_binder rom_utils rom_binder_ndk)
elseif(SPECTRUM_PLATFORM_LINK_MODE STREQUAL "stubs")
    set(SPECTRUM_PLATFORM_LINK_DIR "" CACHE PATH "Explicit link-only platform library directory" FORCE)
    enable_language(ASM)
    add_subdirectory(platform/link_stubs)
    set(SPECTRUM_PLATFORM_LINK_TARGETS spectrum_binder_link_stub spectrum_utils_link_stub binder_ndk)
else()
    message(FATAL_ERROR "SPECTRUM_PLATFORM_LINK_MODE must be stubs or external")
endif()

set(ZN_HEADERS "${SPECTRUM_ROOT}/build/vendor/zygisk-next")
set(PLATFORM_HEADERS "${SPECTRUM_ROOT}/third_party/aosp/include")

add_library(spectrum_payload SHARED
    android/bootstrap.cpp
    android/capture_hooks.cpp
    android/service.cpp
    platform/bridge.cpp
)
target_include_directories(spectrum_payload PRIVATE include "${ZN_HEADERS}")
set_source_files_properties(platform/bridge.cpp PROPERTIES INCLUDE_DIRECTORIES "${PLATFORM_HEADERS}")
target_link_libraries(spectrum_payload PRIVATE
    spectrum_core
    spectrum_aidl
    ${SPECTRUM_PLATFORM_LINK_TARGETS}
    android
    log
    dl
)
spectrum_optimize(spectrum_payload)
target_link_options(spectrum_payload PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/payload.exports")
set_property(TARGET spectrum_payload APPEND PROPERTY LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/payload.exports")

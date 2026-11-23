cmake_minimum_required(VERSION 3.22)
project(SpectrumCoreHarness LANGUAGES CXX)

# Standalone entry point for host or NDK builds without the Android payload dependencies.
set(SPECTRUM_BUILD_CORE_HARNESS ON)
add_subdirectory(.. core)

enable_testing()
add_test(NAME spectrum_core_checks COMMAND spectrum_core_checks)

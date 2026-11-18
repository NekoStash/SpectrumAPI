# These DSOs are linker inputs only. They must never be installed or packaged.
# A trap prevents an accidental load from behaving like a fake Binder runtime.
function(spectrum_link_stub target soname)
    add_library(${target} SHARED "${soname}.S")
    set_target_properties(${target} PROPERTIES
        LINKER_LANGUAGE C
        OUTPUT_NAME "${soname}"
        LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/link-only")
    target_link_options(${target} PRIVATE
        -nostdlib -Wl,--as-needed -Wl,-z,defs -Wl,-z,noexecstack
        "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/${soname}.map")
    set_property(TARGET ${target} APPEND PROPERTY LINK_DEPENDS
        "${CMAKE_CURRENT_SOURCE_DIR}/${soname}.map")
endfunction()

spectrum_link_stub(spectrum_binder_link_stub binder)
spectrum_link_stub(spectrum_utils_link_stub utils)

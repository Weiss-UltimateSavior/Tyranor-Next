# Loaded after upstream project(impacto), before its FetchContent declarations.
# FetchContent uses the first declaration. No upstream source file is edited.
include_guard(GLOBAL)
include(FetchContent)
file(READ "${TYRANOR_IMPACTO_SOURCE_LOCK}" _tyranor_lock)
foreach(_tyranor_dep libatrac9 imgui magic_enum)
    string(JSON _tyranor_url GET "${_tyranor_lock}" "${_tyranor_dep}" url)
    string(JSON _tyranor_revision GET "${_tyranor_lock}" "${_tyranor_dep}" revision)
    string(LENGTH "${_tyranor_revision}" _tyranor_length)
    if(NOT _tyranor_length EQUAL 40 OR NOT _tyranor_revision MATCHES "^[0-9a-f]+$")
        message(FATAL_ERROR "Expected a full locked revision for ${_tyranor_dep}")
    endif()
    if(_tyranor_dep STREQUAL "libatrac9")
        # This is the existing upstream dependency recipe, not a TYN patch.
        FetchContent_Declare(libatrac9
            GIT_REPOSITORY "${_tyranor_url}" GIT_TAG "${_tyranor_revision}"
            PATCH_COMMAND "${CMAKE_COMMAND}" -E copy
                "${CMAKE_CURRENT_SOURCE_DIR}/vendor/patches/LibAtrac9/CMakeLists.txt"
                <SOURCE_DIR>/CMakeLists.txt
            SYSTEM EXCLUDE_FROM_ALL)
    else()
        FetchContent_Declare(${_tyranor_dep}
            GIT_REPOSITORY "${_tyranor_url}" GIT_TAG "${_tyranor_revision}"
            SYSTEM)
    endif()
endforeach()

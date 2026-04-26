cmake_minimum_required(VERSION 3.22)

project(mira_pty_native C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

set(MIRA_PTY_CORE_SOURCES
    src/pty/pty_core.c
    src/posix/pty_spawn_posix.c
)

set(MIRA_SHELL_CORE_SOURCES
    src/shell/shell_core.c
    src/shell/builtin_shell.c
)

if(ANDROID)
    add_library(mira_pty SHARED
        ${MIRA_PTY_CORE_SOURCES}
        src/platform/android/pty_pair_android.c
        bridge/android/jni/mira_pty_jni.c
    )
elseif(APPLE)
    add_library(mira_pty STATIC
        ${MIRA_PTY_CORE_SOURCES}
        src/platform/darwin/pty_pair_darwin.c
        bridge/ios/mira_pty_ios_shim.c
    )
else()
    add_library(mira_pty SHARED
        ${MIRA_PTY_CORE_SOURCES}
        src/platform/android/pty_pair_android.c
    )
endif()

if(ANDROID)
    target_link_libraries(mira_pty
        PRIVATE
            log
    )
endif()

target_include_directories(mira_pty
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
        ${CMAKE_CURRENT_SOURCE_DIR}/bridge/ios
)

target_compile_definitions(mira_pty
    PRIVATE
        _GNU_SOURCE
        _XOPEN_SOURCE=600
)

add_library(mira_shell STATIC
    ${MIRA_SHELL_CORE_SOURCES}
)

target_include_directories(mira_shell
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
)

target_link_libraries(mira_shell
    PRIVATE
        mira_pty
)

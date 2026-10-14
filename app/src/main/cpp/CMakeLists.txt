cmake_minimum_required(VERSION 3.22.1)
project(nocturne C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------- FluidSynth (LGPL, shared)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(osal "cpp11" CACHE STRING "" FORCE)
set(enable-floats ON CACHE BOOL "" FORCE)
foreach(opt enable-libsndfile enable-openmp enable-alsa enable-jack enable-pulseaudio enable-pipewire
            enable-sdl3 enable-dbus enable-readline enable-ladspa enable-network enable-ipv6 enable-oss
            enable-midishare enable-portaudio enable-opensles enable-oboe enable-native-dls
            enable-signalsmith enable-aufile enable-systemd enable-coverage enable-profiling)
    set(${opt} OFF CACHE BOOL "" FORCE)
endforeach()
add_subdirectory(third_party/fluidsynth EXCLUDE_FROM_ALL)
if(TARGET fluidsynth)
    set_target_properties(fluidsynth PROPERTIES EXCLUDE_FROM_ALL TRUE)
endif()

# ---------------------------------------------------------------- Oboe (prefab)
find_package(oboe REQUIRED CONFIG)

# ---------------------------------------------------------------- Nocturne engine
add_library(nocturne SHARED
        jni/NativeBridge.cpp
        jni/AudioOutput.cpp
        engine/Engine.cpp
        engine/Sf3Transcoder.cpp
        third_party/stb_vorbis_impl.c)

target_include_directories(nocturne PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(nocturne PRIVATE -Wall -Wextra -Wno-unused-parameter -O3 -ffast-math)
set_source_files_properties(third_party/stb_vorbis_impl.c PROPERTIES COMPILE_FLAGS "-w")
target_link_libraries(nocturne PRIVATE libfluidsynth oboe::oboe android log)

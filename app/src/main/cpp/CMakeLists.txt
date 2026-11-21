# CMake build configuration for KHATAWAS
# Simplified Technical English (ASD STE-100) compliant.

cmake_minimum_required(VERSION 3.18.1)
project("khatawas")

set(RNNOISE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise)

# Define source files for the RNNoise static library
set(RNNOISE_SOURCES
    ${RNNOISE_DIR}/src/denoise.c
    ${RNNOISE_DIR}/src/rnn.c
    ${RNNOISE_DIR}/src/pitch.c
    ${RNNOISE_DIR}/src/kiss_fft.c
    ${RNNOISE_DIR}/src/celt_lpc.c
    ${RNNOISE_DIR}/src/nnet.c
    ${RNNOISE_DIR}/src/nnet_default.c
    ${RNNOISE_DIR}/src/parse_lpcnet_weights.c
    ${RNNOISE_DIR}/src/rnnoise_tables.c
    ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise_data.c
)

# Create static library for RNNoise
add_library(rnnoise_static STATIC ${RNNOISE_SOURCES})

# Set include directories for RNNoise headers and embedded weights
target_include_directories(rnnoise_static PUBLIC
    ${RNNOISE_DIR}/include
    ${RNNOISE_DIR}/src
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_definitions(rnnoise_static PRIVATE -DRNNOISE_BUILD)

# Create shared library for JNI wrapper
add_library(khatawas_jni SHARED rnnoise_wrapper.cpp)

target_include_directories(khatawas_jni PRIVATE
    ${RNNOISE_DIR}/include
    ${RNNOISE_DIR}/src
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Link static RNNoise library and Android system libraries
target_link_libraries(
    khatawas_jni
    rnnoise_static
    log
    android
)

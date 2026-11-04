cmake_minimum_required(VERSION 3.18)
project(gcjni C CXX)

set(CMAKE_CXX_STANDARD 17)

# libjpeg-turbo (BSD/IJG), vendored in camera-app/third_party. Its build refuses
# add_subdirectory(), so it is built as an external project with the same NDK toolchain:
# static, NEON, encoder and decoder only, no TurboJPEG API or tools.
include(ExternalProject)
set(LJT_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libjpeg-turbo)
set(LJT_PREFIX ${CMAKE_BINARY_DIR}/libjpeg-turbo)
ExternalProject_Add(libjpeg-turbo-ext
    SOURCE_DIR ${LJT_SRC}
    PREFIX ${LJT_PREFIX}
    CMAKE_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DANDROID_ARM_NEON=ON
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        -DENABLE_SHARED=OFF
        -DENABLE_STATIC=ON
        -DWITH_TURBOJPEG=OFF
        -DWITH_TOOLS=OFF
        -DWITH_TESTS=OFF
        -DCMAKE_INSTALL_PREFIX=${LJT_PREFIX}/install
        -DCMAKE_INSTALL_LIBDIR=lib
    BUILD_BYPRODUCTS ${LJT_PREFIX}/install/lib/libjpeg.a
)
file(MAKE_DIRECTORY ${LJT_PREFIX}/install/include)
add_library(jpeg STATIC IMPORTED)
set_target_properties(jpeg PROPERTIES
    IMPORTED_LOCATION ${LJT_PREFIX}/install/lib/libjpeg.a
    INTERFACE_INCLUDE_DIRECTORIES ${LJT_PREFIX}/install/include)
add_dependencies(jpeg libjpeg-turbo-ext)

# The shared image core, also built for the PC tools
set(GCCORE_WITH_JPEG ON)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../gccore ${CMAKE_BINARY_DIR}/gccore)

add_library(gcjni SHARED gcjni.cpp)
target_link_libraries(gcjni PRIVATE gccore log)
target_compile_options(gcjni PRIVATE -Wall -Wextra -O2)

# gccore: the image core shared by the camera app (NDK, armeabi-v7a) and the PC tools.
cmake_minimum_required(VERSION 3.18)
project(gccore CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(gccore STATIC
    src/tiff.cpp
    src/raw.cpp
    src/dng.cpp
    src/isp.cpp
    src/lens.cpp
)
target_include_directories(gccore PUBLIC include)
target_compile_options(gccore PRIVATE -Wall -Wextra -O2)

# Reading the ISP's JPEG (matchIsp) needs libjpeg(-turbo); the parent provides a `jpeg` target
if(GCCORE_WITH_JPEG)
    target_sources(gccore PRIVATE src/jpeg.cpp)
    target_link_libraries(gccore PUBLIC jpeg)
    target_compile_definitions(gccore PUBLIC GCCORE_WITH_JPEG=1)
endif()

# Host tools (not built for Android)
if(NOT ANDROID)
    add_executable(gcdng tools/gcdng.cpp)
    target_link_libraries(gcdng PRIVATE gccore)
endif()

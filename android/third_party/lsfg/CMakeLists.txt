# liblsfg_bridge.so: lsfg-vk 1.0 frame generation for Zakuroishi. See README.md for where each part comes from.
cmake_minimum_required(VERSION 3.22)
project(lsfg_bridge LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
add_compile_options(-fPIC -w -fexceptions)

file(GLOB FRAMEGEN CONFIGURE_DEPENDS framegen/src/*/*.cpp framegen/v3.1p_src/*.cpp framegen/v3.1p_src/shaders/*.cpp)
add_library(framegen OBJECT ${FRAMEGEN} volk/volk.c)
target_compile_definitions(framegen PRIVATE VK_USE_PLATFORM_ANDROID_KHR=1)
target_include_directories(framegen PUBLIC framegen/public PRIVATE framegen/include framegen/v3.1p_include volk)

file(GLOB DXBC CONFIGURE_DEPENDS dxbc/src/dxbc/*.cpp dxbc/src/spirv/*.cpp dxbc/src/util/*.cpp dxbc/src/util/log/*.cpp)
add_library(translator OBJECT translate.cpp ${DXBC}
    pe-parse/pe-parser-library/src/buffer.cpp pe-parse/pe-parser-library/src/parse.cpp
    pe-parse/pe-parser-library/src/unicode_codecvt.cpp)
target_include_directories(translator PRIVATE dxbc/src spirv pe-parse/pe-parser-library/include)

add_library(lsfg_bridge SHARED bridge.cpp $<TARGET_OBJECTS:framegen> $<TARGET_OBJECTS:translator>)
target_include_directories(lsfg_bridge PRIVATE framegen/public)
target_link_libraries(lsfg_bridge PRIVATE log android dl)

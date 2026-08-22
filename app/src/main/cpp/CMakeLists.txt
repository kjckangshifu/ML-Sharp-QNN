cmake_minimum_required(VERSION 3.22.1)
project(sharp_qnn LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 输出库名 (Kotlin JNI System.loadLibrary("sharp_qnn"))
set(SHARP_LIB_NAME sharp_qnn)

# QNN SDK 路径 (由 build.gradle.kts 传入)
message(STATUS "QNN_SDK_DIR = ${QNN_SDK_DIR}")
message(STATUS "SHARP_LIB_NAME = ${SHARP_LIB_NAME}")

# ============== 头文件路径 ==============
# QNN SDK 头文件
include_directories(${QNN_SDK_DIR}/include/QNN)
include_directories(${QNN_SDK_DIR}/include/QNN/HTP)
# 本项目 native 头文件
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/qnn)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/core)

# ============== 编译选项 ==============
add_compile_options(
    -O2
    -fexceptions
    -frtti
    -Wall
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-unused-function
    # QNN_API 由 QnnCommon.h 默认定义为空 (仅 QNN 库自身构建时才需 visibility)
    # 我们只是消费 QNN 头文件, 不需要导出 QNN 符号
)

# ============== OpenMP (Android NDK) ==============
find_package(OpenMP)
if(OpenMP_CXX_FOUND)
    message(STATUS "OpenMP found: ${OpenMP_CXX_FLAGS}")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${OpenMP_CXX_FLAGS}")
else()
    message(WARNING "OpenMP not found; kNN merge will be single-threaded")
endif()

# ============== GaussSimplify (PLY optimization library) ==============
set(GAUSS_SIMPLIFY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../GaussSimplify)
set(GAUSS_SIMPLIFY_SOURCES
    ${GAUSS_SIMPLIFY_DIR}/src/simplify.cpp
    ${GAUSS_SIMPLIFY_DIR}/src/simplify_activate.cpp
    ${GAUSS_SIMPLIFY_DIR}/src/simplify_prune.cpp
    ${GAUSS_SIMPLIFY_DIR}/src/simplify_merge.cpp
)

add_library(gauss_simplify_lib STATIC ${GAUSS_SIMPLIFY_SOURCES})
target_include_directories(gauss_simplify_lib PUBLIC
    ${GAUSS_SIMPLIFY_DIR}/include
    ${GAUSS_SIMPLIFY_DIR}/src
    ${GAUSS_SIMPLIFY_DIR}/external
    ${CMAKE_CURRENT_SOURCE_DIR}/gf_stubs
)
target_compile_definitions(gauss_simplify_lib PRIVATE GS_NO_GAUSSFORGE=1)
if(OpenMP_CXX_FOUND)
    target_link_libraries(gauss_simplify_lib PRIVATE OpenMP::OpenMP_CXX)
endif()

# ============== SHARP core library (ported C/C++ sources) ==============
set(SHARP_CORE_SOURCES
    core/normalizer.c
    core/pyramid.c
    core/split_patches.c
    core/merge_patches.c
    core/depth_from_disparity.c
    core/initializer.c
    core/composer.c
    core/unproject.c
    core/save_ply.c
    core/exif.c
    core/prep_input.cpp
    # pipeline.c changed to library mode (removed main, exposes pre/merge/post functions)
    core/pipeline_core.c
)

add_library(sharp_core STATIC ${SHARP_CORE_SOURCES})
target_link_libraries(sharp_core m)

# ============== QNN HTP 集成层 ==============
set(QNN_SOURCES
    qnn/qnn_runtime.cpp
    qnn/qnn_dlc_compiler.cpp
    qnn/qnn_tensor.cpp
)

# ============== JNI 桥接层 ==============
set(JNI_SOURCES
    sharp_jni.cpp
    core/ply_optimize.cpp
)

# ============== 主共享库 libsharp_qnn.so ==============
add_library(${SHARP_LIB_NAME} SHARED
    ${QNN_SOURCES}
    ${JNI_SOURCES}
)

# 链接: sharp_core + Android 系统库
# 注意: QNN .so 不参与链接, 运行时 dlopen
target_link_libraries(${SHARP_LIB_NAME} PRIVATE
    sharp_core
    gauss_simplify_lib
    log     # Android logcat
    dl      # dlopen/dlsym
    android # AssetManager
)

# OpenMP 链接 (ply_optimize -> gauss_simplify 需要)
if(OpenMP_CXX_FOUND)
    target_link_libraries(${SHARP_LIB_NAME} PRIVATE OpenMP::OpenMP_CXX)
endif()

# 设置 SHARP_LIB_NAME (默认 sharp_qnn, 已在文件开头设置)
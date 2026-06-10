# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

cmake_minimum_required(VERSION 3.22.1)
project("andpass")
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp)

# OpenSSL
set(OPENSSL_ROOT ${CMAKE_SOURCE_DIR}/../../../../external/openssl/install-android-arm64)
add_library(crypto SHARED IMPORTED)
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libcrypto.so)
add_library(ssl SHARED IMPORTED)
set_target_properties(ssl PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libssl.so)
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE ${OPENSSL_ROOT}/include)

# libgit2
set(LIBGIT2_ROOT ${CMAKE_SOURCE_DIR}/../../../../external/libgit2/install-android-arm64)
add_library(git2 SHARED IMPORTED)
set_target_properties(git2 PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libgit2.so)
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE ${LIBGIT2_ROOT}/include)

# RNP
set(RNP_ROOT ${CMAKE_SOURCE_DIR}/../../../../external/rnp/install-android-arm64)
add_library(rnp SHARED IMPORTED)
set_target_properties(rnp PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/librnp.so)
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE ${RNP_ROOT}/include)

# Linking
target_link_libraries(${CMAKE_PROJECT_NAME}
        git2
        ssl
        crypto
        rnp
        android
        log)

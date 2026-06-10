cd external/rnp

rm -rf build-android-inspect

cmake -S . -B build-android-inspect -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_TESTING=OFF \
  -DBUILD_SHARED_LIBS=ON \
  -DCRYPTO_BACKEND=openssl \
  -DOPENSSL_ROOT_DIR:PATH="$PWD/../openssl/install-android-arm64" \
  -DOPENSSL_INCLUDE_DIR:PATH="$PWD/../openssl/install-android-arm64/include" \
  -DOPENSSL_CRYPTO_LIBRARY:FILEPATH="$PWD/../openssl/install-android-arm64/lib/libcrypto.so" \
  -DOPENSSL_SSL_LIBRARY:FILEPATH="$PWD/../openssl/install-android-arm64/lib/libssl.so" \
  -DBZIP2_INCLUDE_DIR:PATH="$PWD/../bzip2/install-android-arm64/include" \
  -DBZIP2_LIBRARY_RELEASE:FILEPATH="$PWD/../bzip2/install-android-arm64/lib/libbz2.a" \
  -DBZIP2_LIBRARIES:FILEPATH="$PWD/../bzip2/install-android-arm64/lib/libbz2.a" \
  -DJSON-C_INCLUDE_DIR:PATH="$PWD/../json-c/install-android-arm64/include/json-c" \
  -DJSON-C_LIBRARY:FILEPATH="$PWD/../json-c/install-android-arm64/lib/libjson-c.a" \
  2>&1 | tee /tmp/rnp-android-config.log

cmake --build build-android-inspect --parallel
cmake --install build-android-inspect --prefix "$PWD/install-android-arm64"

cd ../..

cp external/rnp/install-android-arm64/lib/librnp.so \
   app/src/main/jniLibs/arm64-v8a/librnp.so

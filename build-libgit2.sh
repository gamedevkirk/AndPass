cd ./external/libgit2

rm -rf build-android-arm64 install-android-arm64

cmake -S . -B build-android-arm64 -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$PWD/install-android-arm64" \
  -DBUILD_SHARED_LIBS=ON \
  -DBUILD_TESTS=OFF \
  -DUSE_HTTPS=OpenSSL \
  -DOPENSSL_ROOT_DIR="$PWD/../openssl/install-android-arm64" \
  -DOPENSSL_INCLUDE_DIR="$PWD/../openssl/install-android-arm64/include" \
  -DOPENSSL_SSL_LIBRARY="$PWD/../openssl/install-android-arm64/lib/libssl.so" \
  -DOPENSSL_CRYPTO_LIBRARY="$PWD/../openssl/install-android-arm64/lib/libcrypto.so" \
  -DUSE_SSH=OFF \
  -DUSE_GSSAPI=OFF

cmake --build build-android-arm64 --parallel
cmake --install build-android-arm64

echo ""
echo "Build completed --- Verifying output"

"$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -d install-android-arm64/lib/libgit2.so

echo ""
echo "Copying output files"

cd ../../
mkdir -p app/src/main/jniLibs/arm64-v8a
cp ./external/libgit2/install-android-arm64/lib/libgit2.so \
   ./app/src/main/jniLibs/arm64-v8a/libgit2.so


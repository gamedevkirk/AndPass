cd external/json-c

rm -rf build-android-arm64 install-android-arm64

cmake -S . -B build-android-arm64 -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$PWD/install-android-arm64" \
  -DBUILD_SHARED_LIBS=OFF \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DBUILD_TESTING=OFF \
  -DDISABLE_WERROR=ON \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5

cmake --build build-android-arm64 --parallel
cmake --install build-android-arm64

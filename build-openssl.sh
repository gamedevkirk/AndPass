cd ./external/openssl

make clean || true
rm -rf install-android-arm64

./Configure android-arm64 \
  -D__ANDROID_API__=24 \
  shared \
  no-tests \
  --prefix="$PWD/install-android-arm64" \
  --openssldir="$PWD/install-android-arm64/ssl"

make -j"$(nproc)"
make install_sw

cd ../../

cp external/openssl/install-android-arm64/lib/libssl.so \
   app/src/main/jniLibs/arm64-v8a/libssl.so

cp external/openssl/install-android-arm64/lib/libcrypto.so \
   app/src/main/jniLibs/arm64-v8a/libcrypto.so

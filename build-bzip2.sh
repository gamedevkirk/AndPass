cd external/bzip2

rm -f *.o libbz2.a
rm -rf install-android-arm64

mkdir -p install-android-arm64/include
mkdir -p install-android-arm64/lib

make libbz2.a \
  CC="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang" \
  AR="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar" \
  RANLIB="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ranlib" \
  CFLAGS="-fPIC -O2 -D_FILE_OFFSET_BITS=64"

cp bzlib.h install-android-arm64/include/
cp libbz2.a install-android-arm64/lib/


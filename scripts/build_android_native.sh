#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -z "${ANDROID_NDK_ROOT:-}" ]; then
    if [ -n "${ANDROID_NDK_HOME:-}" ]; then
        ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
    elif [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME:-}/ndk" ]; then
        ANDROID_NDK_ROOT=$(ls -d "$ANDROID_HOME/ndk/"* 2>/dev/null | sort -V | tail -n 1 || true)
    fi
fi

if [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -d "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt" ]; then
    TOOLCHAIN_BIN=$(ls -d "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/"*"/bin" 2>/dev/null | head -n 1)
    export PATH="$TOOLCHAIN_BIN:$PATH"
fi

API_LEVEL="${ANDROID_API_LEVEL:-21}"

build_arch() {
    local ARCH="$1"
    local TARGET_TRIPLE="$2"
    local JNI_ABI="$3"

    echo "=== Building Diffusion native libraries for $ARCH ($JNI_ABI) ==="
    
    if command -v "${TARGET_TRIPLE}${API_LEVEL}-clang" &> /dev/null; then
        export CC="${TARGET_TRIPLE}${API_LEVEL}-clang"
        export CXX="${TARGET_TRIPLE}${API_LEVEL}-clang++"
        export AR="llvm-ar"
    fi

    rm -rf build .lock-waf*

    python3 ./waf configure -T release
    python3 ./waf build

    mkdir -p "android/app/src/main/jniLibs/$JNI_ABI"
    
    # copy client
    for f in build/client/*client*.so; do
        if [ -f "$f" ]; then
            cp -a "$f" "android/app/src/main/jniLibs/$JNI_ABI/"
        fi
    done

    # copy server
    for f in build/server/*server*.so; do
        if [ -f "$f" ]; then
            cp -a "$f" "android/app/src/main/jniLibs/$JNI_ABI/"
        fi
    done

    # copy menu
    for f in build/3rd-party/mainui_cpp/*menu*.so; do
        if [ -f "$f" ]; then
            cp -a "$f" "android/app/src/main/jniLibs/$JNI_ABI/"
        fi
    done

    # copy libc++_shared.so from NDK
    local LIBCXX_SO=""
    if [ -n "${ANDROID_NDK_ROOT:-}" ]; then
        LIBCXX_SO=$(find "$ANDROID_NDK_ROOT" -name "libc++_shared.so" 2>/dev/null | grep "$TARGET_TRIPLE" | head -n 1 || true)
        if [ -z "$LIBCXX_SO" ]; then
            case "$ARCH" in
                arm64) LIBCXX_SO=$(find "$ANDROID_NDK_ROOT" -name "libc++_shared.so" 2>/dev/null | grep -E "aarch64|arm64" | head -n 1 || true) ;;
                arm) LIBCXX_SO=$(find "$ANDROID_NDK_ROOT" -name "libc++_shared.so" 2>/dev/null | grep -E "arm-linux-androideabi|armv7" | head -n 1 || true) ;;
                x86) LIBCXX_SO=$(find "$ANDROID_NDK_ROOT" -name "libc++_shared.so" 2>/dev/null | grep -E "i686|/x86/" | head -n 1 || true) ;;
                x86_64) LIBCXX_SO=$(find "$ANDROID_NDK_ROOT" -name "libc++_shared.so" 2>/dev/null | grep -E "x86_64" | head -n 1 || true) ;;
            esac
        fi
    fi
    if [ -z "$LIBCXX_SO" ] && [ -f "/data/data/com.termux/files/usr/lib/libc++_shared.so" ] && [ "$ARCH" = "arm64" ]; then
        LIBCXX_SO="/data/data/com.termux/files/usr/lib/libc++_shared.so"
    fi
    if [ -n "$LIBCXX_SO" ] && [ -f "$LIBCXX_SO" ]; then
        echo "Copying libc++_shared.so from $LIBCXX_SO to android/app/src/main/jniLibs/$JNI_ABI/"
        cp -a "$LIBCXX_SO" "android/app/src/main/jniLibs/$JNI_ABI/libc++_shared.so"
    fi
}

ARCH_TARGET="${1:-arm64}"
case "$ARCH_TARGET" in
    arm64|aarch64)
        build_arch "arm64" "aarch64-linux-android" "arm64-v8a"
        ;;
    arm|armv7|armeabi-v7a)
        build_arch "arm" "armv7a-linux-androideabi" "armeabi-v7a"
        ;;
    x86|i686)
        build_arch "x86" "i686-linux-android" "x86"
        ;;
    x86_64|amd64)
        build_arch "x86_64" "x86_64-linux-android" "x86_64"
        ;;
    all)
        build_arch "arm64" "aarch64-linux-android" "arm64-v8a"
        build_arch "arm" "armv7a-linux-androideabi" "armeabi-v7a"
        build_arch "x86" "i686-linux-android" "x86"
        build_arch "x86_64" "x86_64-linux-android" "x86_64"
        ;;
    *)
        echo "Unknown architecture: $ARCH_TARGET"
        exit 1
        ;;
esac

echo "Native libraries successfully built and placed in jniLibs!"

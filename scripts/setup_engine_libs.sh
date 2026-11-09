#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

JNI_DIR="android/app/src/main/jniLibs"
ENGINE_PREBUILTS_DIR="build/engine_prebuilts"

# 1. check if all 4 engine ABI libs exist in ENGINE_PREBUILTS_DIR
NEED_BUILD=0
for ABI in arm64-v8a armeabi-v7a x86 x86_64; do
    if [ ! -f "$ENGINE_PREBUILTS_DIR/$ABI/libxash.so" ]; then
        NEED_BUILD=1
        break
    fi
done

if [ "$NEED_BUILD" -eq 1 ]; then
    echo "=== Engine libraries missing from cache, building Xash3D engine from source ==="
    bash scripts/build_engine_native.sh all
fi

# 2. copy engine prebuilts to jniLibs
echo "=== Installing engine libraries to jniLibs ==="
for ABI in arm64-v8a armeabi-v7a x86 x86_64; do
    mkdir -p "$JNI_DIR/$ABI"
    if [ -d "$ENGINE_PREBUILTS_DIR/$ABI" ]; then
        cp -a "$ENGINE_PREBUILTS_DIR/$ABI"/*.so "$JNI_DIR/$ABI/"
    fi
done

echo "=== Copying Diffusion game libraries ==="
# arm64-v8a
if [ -f "build/client/libclient_android_arm64.so" ]; then
    cp -a build/client/libclient_android_arm64.so "$JNI_DIR/arm64-v8a/"
fi
if [ -f "build/server/libserver_android_arm64.so" ]; then
    cp -a build/server/libserver_android_arm64.so "$JNI_DIR/arm64-v8a/"
fi
if [ -f "build/3rd-party/mainui_cpp/libmenu_android_arm64.so" ]; then
    cp -a build/3rd-party/mainui_cpp/libmenu_android_arm64.so "$JNI_DIR/arm64-v8a/"
fi

# armeabi-v7a
if [ -f "build/client/libclient_android_armv7hf.so" ]; then
    cp -a build/client/libclient_android_armv7hf.so "$JNI_DIR/armeabi-v7a/"
fi
if [ -f "build/server/libserver_android_armv7hf.so" ]; then
    cp -a build/server/libserver_android_armv7hf.so "$JNI_DIR/armeabi-v7a/"
fi
if [ -f "build/3rd-party/mainui_cpp/libmenu_android_armv7hf.so" ]; then
    cp -a build/3rd-party/mainui_cpp/libmenu_android_armv7hf.so "$JNI_DIR/armeabi-v7a/"
fi

# x86
if [ -f "build/client/libclient_android_i386.so" ]; then
    cp -a build/client/libclient_android_i386.so "$JNI_DIR/x86/"
fi
if [ -f "build/server/libserver_android_i386.so" ]; then
    cp -a build/server/libserver_android_i386.so "$JNI_DIR/x86/"
fi
if [ -f "build/3rd-party/mainui_cpp/libmenu_android_i386.so" ]; then
    cp -a build/3rd-party/mainui_cpp/libmenu_android_i386.so "$JNI_DIR/x86/"
fi

# x86_64
if [ -f "build/client/libclient_android_amd64.so" ]; then
    cp -a build/client/libclient_android_amd64.so "$JNI_DIR/x86_64/"
fi
if [ -f "build/server/libserver_android_amd64.so" ]; then
    cp -a build/server/libserver_android_amd64.so "$JNI_DIR/x86_64/"
fi
if [ -f "build/3rd-party/mainui_cpp/libmenu_android_amd64.so" ]; then
    cp -a build/3rd-party/mainui_cpp/libmenu_android_amd64.so "$JNI_DIR/x86_64/"
fi

# kang libc++_shared.so from ndk
echo "=== Copying libc++_shared.so from NDK ==="
if [ -z "${ANDROID_NDK_ROOT:-}" ]; then
    if [ -n "${ANDROID_NDK_HOME:-}" ]; then
        ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
    elif [ -n "${ANDROID_NDK:-}" ]; then
        ANDROID_NDK_ROOT="$ANDROID_NDK"
    elif [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME:-}/ndk" ]; then
        ANDROID_NDK_ROOT=$(ls -d "$ANDROID_HOME/ndk/"* 2>/dev/null | sort -V | tail -n 1 || true)
    fi
fi

for ABI in arm64-v8a armeabi-v7a x86 x86_64; do
    LIBCXX_SO=""
    if [ -n "${ANDROID_NDK_ROOT:-}" ]; then
        TRIPLE=""
        case "$ABI" in
            arm64-v8a) TRIPLE="aarch64-linux-android" ;;
            armeabi-v7a) TRIPLE="arm-linux-androideabi" ;;
            x86) TRIPLE="i686-linux-android" ;;
            x86_64) TRIPLE="x86_64-linux-android" ;;
        esac
        LIBCXX_SO=$(find "$ANDROID_NDK_ROOT" -name "libc++_shared.so" 2>/dev/null | grep "$TRIPLE" | head -n 1 || true)
        if [ -z "$LIBCXX_SO" ]; then
            case "$ABI" in
                arm64-v8a) LIBCXX_SO=$(find "$ANDROID_NDK_ROOT" -name "libc++_shared.so" 2>/dev/null | grep -E "aarch64|arm64" | head -n 1 || true) ;;
                armeabi-v7a) LIBCXX_SO=$(find "$ANDROID_NDK_ROOT" -name "libc++_shared.so" 2>/dev/null | grep -E "arm-linux-androideabi|armv7" | head -n 1 || true) ;;
                x86) LIBCXX_SO=$(find "$ANDROID_NDK_ROOT" -name "libc++_shared.so" 2>/dev/null | grep -E "i686|/x86/" | head -n 1 || true) ;;
                x86_64) LIBCXX_SO=$(find "$ANDROID_NDK_ROOT" -name "libc++_shared.so" 2>/dev/null | grep -E "x86_64" | head -n 1 || true) ;;
            esac
        fi
    fi
    if [ -z "$LIBCXX_SO" ] && [ -f "/data/data/com.termux/files/usr/lib/libc++_shared.so" ] && [ "$ABI" = "arm64-v8a" ]; then
        LIBCXX_SO="/data/data/com.termux/files/usr/lib/libc++_shared.so"
    fi
    if [ -n "$LIBCXX_SO" ] && [ -f "$LIBCXX_SO" ]; then
        echo "Copying libc++_shared.so from $LIBCXX_SO to $JNI_DIR/$ABI/"
        cp -a "$LIBCXX_SO" "$JNI_DIR/$ABI/libc++_shared.so"
    fi
done

echo "Done, all engine and game libraries installed in $JNI_DIR"

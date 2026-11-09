#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

SDL_VERSION="2.32.10"
FFMPEG_VERSION="9.0"

# locate Android NDK
if [ -z "${ANDROID_NDK_ROOT:-}" ]; then
    if [ -n "${ANDROID_NDK_HOME:-}" ]; then
        ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
    elif [ -n "${ANDROID_NDK:-}" ]; then
        ANDROID_NDK_ROOT="$ANDROID_NDK"
    elif [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME:-}/ndk" ]; then
        ANDROID_NDK_ROOT=$(ls -d "$ANDROID_HOME/ndk/"* 2>/dev/null | sort -V | tail -n 1 || true)
    fi
fi

if [ -z "${ANDROID_NDK_ROOT:-}" ] || [ ! -d "${ANDROID_NDK_ROOT:-}" ]; then
    echo "##[error]Android NDK not found! Please set ANDROID_NDK_ROOT or ANDROID_NDK_HOME."
    exit 1
fi

export ANDROID_NDK="$ANDROID_NDK_ROOT"
export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"

XASH_ROOT="3rd-party/xash3d-fwgs"
ENGINE_PREBUILTS_DIR="$(pwd)/build/engine_prebuilts"
JNI_DIR="$(pwd)/android/app/src/main/jniLibs"

download_file() {
    local URL="$1"
    local OUT="$2"
    echo "Downloading: $URL -> $OUT"
    rm -f "$OUT"

    # if it is a github release asset and gh cli is available, try gh release download first
    if command -v gh >/dev/null 2>&1 && echo "$URL" | grep -q 'github.com/[^/]*/[^/]*/releases/download/'; then
        local REPO_OWNER_NAME TAG_NAME ASSET_NAME
        REPO_OWNER_NAME=$(echo "$URL" | sed -E 's#https://github.com/([^/]+/[^/]+)/releases/download/.*#\1#')
        TAG_NAME=$(echo "$URL" | sed -E 's#https://github.com/[^/]+/[^/]+/releases/download/([^/]+)/.*#\1#')
        ASSET_NAME=$(basename "$URL")

        echo "Attempting download via gh release ($REPO_OWNER_NAME @ $TAG_NAME, asset: $ASSET_NAME)..."
        if gh release download "$TAG_NAME" -R "$REPO_OWNER_NAME" -p "$ASSET_NAME" -O "$OUT" --clobber; then
            echo "Successfully downloaded $ASSET_NAME via gh"
            return 0
        fi
        echo "gh release download failed, falling back to curl/wget..."
    fi

    # fallback to curl with retries
    if command -v curl >/dev/null 2>&1; then
        echo "Downloading with curl..."
        if curl -fSL --retry 5 --retry-delay 5 --retry-connrefused --connect-timeout 30 "$URL" -o "$OUT"; then
            return 0
        fi
    fi

    # fallback to wget with retries
    if command -v wget >/dev/null 2>&1; then
        echo "Downloading with wget..."
        if wget --tries=5 --waitretry=5 "$URL" -O "$OUT"; then
            return 0
        fi
    fi

    echo "##[error]Failed to download $URL"
    return 1
}

echo "=== Ensuring Xash3D FWGS repository is present ==="
if [ ! -d "$XASH_ROOT" ]; then
    mkdir -p 3rd-party
    git clone --depth 1 https://github.com/FWGS/xash3d-fwgs "$XASH_ROOT"
fi

pushd "$XASH_ROOT" > /dev/null

echo "=== Initializing engine submodules ==="
git submodule update --init --recursive
git submodule update --init --checkout 3rdparty/nanogl 3rdparty/gl4es/gl4es

echo "=== Ensuring SDL2 source is present ==="
mkdir -p 3rdparty
if [ ! -d "3rdparty/SDL/src" ]; then
    echo "Downloading SDL $SDL_VERSION..."
    download_file "https://github.com/libsdl-org/SDL/releases/download/release-$SDL_VERSION/SDL2-$SDL_VERSION.tar.gz" sdl2.tar.gz
    tar -xzf sdl2.tar.gz
    rm -rf 3rdparty/SDL
    mv "SDL2-$SDL_VERSION" 3rdparty/SDL
    rm -f sdl2.tar.gz
fi

SDL_SRC_DIR="$(pwd)/3rdparty/SDL"
XASH_ABS_DIR="$(pwd)"

build_abi() {
    local ABI="$1"
    local WAF_ARCH="$2"
    local FFMPEG_ARCH="$3"

    echo "=========================================================="
    echo "=== Building Xash3D Engine for $ABI ==="
    echo "=========================================================="

    # 1. fetch prebuilt ffmpeg if not present
    local FFMPEG_DIR="$XASH_ABS_DIR/3rdparty/ffmpeg/$ABI"
    if [ ! -f "$FFMPEG_DIR/lib/libavcodec.so" ]; then
        echo "Fetching prebuilt FFmpeg for $ABI ($FFMPEG_ARCH)..."
        mkdir -p "$FFMPEG_DIR"
        local FFMPEG_TAR="ffmpeg-n${FFMPEG_VERSION}-latest-${FFMPEG_ARCH}-lgpl-shared-minimal-${FFMPEG_VERSION}.tar.xz"
        download_file "https://github.com/FWGS/FFmpeg-Builds/releases/download/latest/$FFMPEG_TAR" "$FFMPEG_TAR"
        tar -xJf "$FFMPEG_TAR" -C "$FFMPEG_DIR" --strip-components=1
        rm -f "$FFMPEG_TAR"
    fi

    # 2. build SDL2 via cmake + ninja
    local SDL_BUILD_DIR="$XASH_ABS_DIR/build-sdl-$ABI"
    echo "Building SDL2 for $ABI..."
    cmake -B "$SDL_BUILD_DIR" -H"$SDL_SRC_DIR" \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=android-21 \
        -DCMAKE_BUILD_TYPE=Release \
        -DSDL_RENDER=OFF \
        -DSDL_POWER=OFF \
        -DSDL_VULKAN=OFF \
        -DSDL_DISKAUDIO=OFF \
        -DSDL_DUMMYAUDIO=OFF \
        -DSDL_DUMMYVIDEO=OFF \
        -DSDL_OFFSCREEN=OFF \
        -DSDL_STATIC=OFF \
        -GNinja
    ninja -C "$SDL_BUILD_DIR" SDL2

    # 3. build xash3d via waf
    echo "Configuring and building Xash3D engine for $ABI..."
    export BUILD_CMAKE_LIBRARY_OUTPUT_DIRECTORY="$SDL_BUILD_DIR"
    export WAFLOCK=".lock-waf_android_${ABI}_build"

    local WAF_OUT="build-$ABI"
    rm -rf "$WAF_OUT" "$WAFLOCK"*

    python3 ./waf configure -T release \
        --android="${WAF_ARCH},,21" \
        -s "$SDL_SRC_DIR" \
        --skip-sdl2-sanity-check \
        --enable-bundled-deps \
        --enable-gl4es \
        --enable-gles1 \
        --enable-gles3compat \
        --enable-ffmpeg \
        --enable-ffmpeg-dlopen \
        --ffmpeg-dir="$FFMPEG_DIR" \
        -o "$WAF_OUT"

    python3 ./waf build -o "$WAF_OUT"

    # 4. copy libs to staging and jniLibs
    local STAGING="$ENGINE_PREBUILTS_DIR/$ABI"
    local DEST="$JNI_DIR/$ABI"
    mkdir -p "$STAGING" "$DEST"

    # SDL2
    cp -a "$SDL_BUILD_DIR/libSDL2.so" "$STAGING/"
    cp -a "$SDL_BUILD_DIR/libSDL2.so" "$DEST/"

    # engine core & filesystem
    cp -a "$WAF_OUT/engine/libxash.so" "$STAGING/"
    cp -a "$WAF_OUT/engine/libxash.so" "$DEST/"

    cp -a "$WAF_OUT/filesystem/libfilesystem_stdio.so" "$STAGING/"
    cp -a "$WAF_OUT/filesystem/libfilesystem_stdio.so" "$DEST/"

    # renderers
    for r in "$WAF_OUT"/ref/gl/libref_*.so; do
        if [ -f "$r" ]; then
            cp -a "$r" "$STAGING/"
            cp -a "$r" "$DEST/"
        fi
    done

    # ffmpeg libraries
    for f in "$FFMPEG_DIR"/lib/*.so; do
        if [ -f "$f" ]; then
            cp -a "$f" "$STAGING/"
            cp -a "$f" "$DEST/"
        fi
    done

    echo "=== Successfully built and staged engine for $ABI ==="
}

TARGET="${1:-all}"

case "$TARGET" in
    arm64|arm64-v8a|aarch64)
        build_abi "arm64-v8a" "arm64-v8a" "androidarm64"
        ;;
    arm|armeabi-v7a|armv7)
        build_abi "armeabi-v7a" "armeabi-v7a" "androidarm32"
        ;;
    x86|i686)
        build_abi "x86" "x86" "android32"
        ;;
    x86_64|amd64)
        build_abi "x86_64" "x86_64" "android64"
        ;;
    all)
        build_abi "arm64-v8a" "arm64-v8a" "androidarm64"
        build_abi "armeabi-v7a" "armeabi-v7a" "androidarm32"
        build_abi "x86" "x86" "android32"
        build_abi "x86_64" "x86_64" "android64"
        ;;
    *)
        echo "Unknown target: $TARGET"
        exit 1
        ;;
esac

popd > /dev/null

echo "All requested engine native libraries built and staged in $ENGINE_PREBUILTS_DIR!"

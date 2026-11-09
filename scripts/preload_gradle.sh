#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

WRAPPER_PROP="android/gradle/wrapper/gradle-wrapper.properties"
if [ ! -f "$WRAPPER_PROP" ]; then
    echo "Gradle wrapper properties not found at $WRAPPER_PROP"
    exit 0
fi

GRADLE_URL=$(grep 'distributionUrl=' "$WRAPPER_PROP" | cut -d= -f2- | sed 's/\\:/:/g')
GRADLE_ZIP=$(basename "$GRADLE_URL")
GRADLE_DIR_NAME="${GRADLE_ZIP%.zip}"
GRADLE_VER=$(echo "$GRADLE_ZIP" | sed -E 's/gradle-(.*)-(bin|all)\.zip/\1/')

HASH_DIR=$(python3 -c "
import hashlib
url = '$GRADLE_URL'
md5 = hashlib.md5(url.encode('utf-8')).digest()
num = int.from_bytes(md5, byteorder='big')
chars = '0123456789abcdefghijklmnopqrstuvwxyz'
res = ''
while num > 0:
    res = chars[num % 36] + res
    num //= 36
print(res)
")

GRADLE_CACHE_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/$GRADLE_DIR_NAME/$HASH_DIR"
mkdir -p "$GRADLE_CACHE_DIR"

if [ -f "$GRADLE_CACHE_DIR/$GRADLE_ZIP" ]; then
    echo "Gradle $GRADLE_VER distribution already cached at $GRADLE_CACHE_DIR/$GRADLE_ZIP"
    exit 0
fi

echo "Preloading Gradle $GRADLE_VER distribution to $GRADLE_CACHE_DIR..."
# try gh release download first
if command -v gh >/dev/null 2>&1; then
    echo "Attempting download via gh release (gradle/gradle-distributions @ v$GRADLE_VER)..."
    if gh release download "v$GRADLE_VER" -R gradle/gradle-distributions -p "$GRADLE_ZIP" -O "$GRADLE_CACHE_DIR/$GRADLE_ZIP" --clobber; then
        echo "Successfully preloaded $GRADLE_ZIP via gh"
        exit 0
    fi
fi

# fallback to curl with retries
if command -v curl >/dev/null 2>&1; then
    echo "Attempting download via curl with retries..."
    if curl -fSL --retry 5 --retry-delay 5 --retry-connrefused --connect-timeout 30 "$GRADLE_URL" -o "$GRADLE_CACHE_DIR/$GRADLE_ZIP"; then
        echo "Successfully preloaded $GRADLE_ZIP via curl"
        exit 0
    fi
fi

# fallback to wget with retries
if command -v wget >/dev/null 2>&1; then
    echo "Attempting download via wget with retries..."
    if wget --tries=5 --waitretry=5 "$GRADLE_URL" -O "$GRADLE_CACHE_DIR/$GRADLE_ZIP"; then
        echo "Successfully preloaded $GRADLE_ZIP via wget"
        exit 0
    fi
fi

echo "Warning: Could not pre-download Gradle distribution; gradlew will attempt to download it."

#!/bin/sh
set -e
cd "$(dirname "$0")/.."

EXTRAS_DIR="3rdparty/extras/xash-extras"

if [ -f "$EXTRAS_DIR/gamecontrollerdb.txt" ]; then
	echo "xash-extras already present at $EXTRAS_DIR."
	exit 0
fi

echo "=== Fetching xash-extras ==="
mkdir -p 3rdparty/extras
git clone --depth 1 https://github.com/FWGS/xash-extras "$EXTRAS_DIR"
echo "Done, xash-extras is at $EXTRAS_DIR"

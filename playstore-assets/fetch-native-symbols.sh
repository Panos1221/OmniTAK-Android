#!/usr/bin/env bash
# Fetch unstripped libmaplibre.so files from MapLibre's GitHub release
# and stage them under app/src/main/jniLibs/<abi>/. AGP picks these up
# in mergeReleaseNativeLibs (jniLibs takes precedence over AAR libs),
# debugSymbolLevel = "FULL" extracts the debug info into BUNDLE-METADATA,
# and stripReleaseDebugSymbols then strips them for the actual APK
# splits — so APK size doesn't change, but Play Console gets symbols
# for native-crash symbolication.
#
# Why this exists: Maven Central serves MapLibre's release.aar with
# .so files already stripped, so AGP has nothing to extract by default.
# This script fetches the GitHub-published unstripped tarball.
#
# Usage: ./playstore-assets/fetch-native-symbols.sh
#
# When you bump org.maplibre.gl:android-sdk in app/build.gradle.kts,
# update MAPLIBRE_VERSION below to match. Only needed for release builds
# you intend to upload to Play Console.

set -euo pipefail

MAPLIBRE_VERSION="11.8.0"
RENDERER="opengl"  # the gradle dep is the OpenGL renderer; vulkan is a separate AAR.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_LIBS_DIR="$REPO_ROOT/app/src/main/jniLibs"
CACHE_DIR="$REPO_ROOT/.gradle/maplibre-symbols-cache"
TARBALL_NAME="debug-symbols-maplibre-android-${RENDERER}-android-v${MAPLIBRE_VERSION}.tar.gz"
TARBALL_URL="https://github.com/maplibre/maplibre-native/releases/download/android-v${MAPLIBRE_VERSION}/${TARBALL_NAME}"
CACHED_TARBALL="$CACHE_DIR/$TARBALL_NAME"

mkdir -p "$CACHE_DIR"

if [[ -f "$CACHED_TARBALL" ]]; then
  echo ">> Using cached tarball: $CACHED_TARBALL"
else
  echo ">> Downloading $TARBALL_URL"
  curl -fL --progress-bar -o "$CACHED_TARBALL" "$TARBALL_URL"
fi

echo ">> Extracting to $JNI_LIBS_DIR/"
mkdir -p "$JNI_LIBS_DIR"
# Tarball layout is `./<abi>/libmaplibre.so` — extract directly into jniLibs.
tar xzf "$CACHED_TARBALL" -C "$JNI_LIBS_DIR"

echo ""
echo ">> Staged unstripped libmaplibre.so:"
ls -lh "$JNI_LIBS_DIR"/*/libmaplibre.so

echo ""
echo ">> Done. Run ./playstore-assets/build-release.sh to bundle an AAB"
echo "   with native debug symbols."

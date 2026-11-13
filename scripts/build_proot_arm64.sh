#!/usr/bin/env bash
set -euo pipefail

# Build arm64-v8a libproot.so and libproot-loader.so from Termux PRoot (GPLv2+)
# and talloc (LGPLv3+) using Android NDK, with 16KB ELF page alignment.
#
# UPSTREAM SOURCES & VERIFICATION:
#   - Termux PRoot repo : https://github.com/termux/proot
#   - Pinned PRoot tag  : v5.1.107.102 (from termux-packages/packages/proot/build.sh)
#   - talloc source     : https://www.samba.org/ftp/talloc/talloc-2.4.2.tar.gz
#
# VERIFY BEFORE CUTTING A RELEASE BUILD:
#   1. Confirm latest Termux PRoot tag/commit & archive SHA256 at:
#      https://github.com/termux/proot/tags and
#      https://github.com/termux/termux-packages/blob/master/packages/proot/build.sh
#   2. Confirm talloc tarball checksum at https://www.samba.org/ftp/talloc/
#   3. Set ANDROID_NDK_HOME (or ANDROID_NDK_ROOT) to Android NDK r26+ (r27c recommended).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${WORK_DIR:-/tmp/terxtreme-proot-build}"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/app/src/main/jniLibs/arm64-v8a}"

# VERIFY: Pinned upstream tag & SHA256 from termux-packages packages/proot/build.sh
PROOT_REPO_URL="${PROOT_REPO_URL:-https://github.com/termux/proot}"
PROOT_TAG="${PROOT_TAG:-v5.1.107.102}"
PROOT_ARCHIVE_URL="${PROOT_ARCHIVE_URL:-${PROOT_REPO_URL}/archive/refs/tags/${PROOT_TAG}.tar.gz}"
# VERIFY: Note GitHub .tar.gz vs .zip checksums differ; check downloaded tarball or set PROOT_TAR_SHA256 to enforce.
PROOT_TAR_SHA256="${PROOT_TAR_SHA256:-}"

TALLOC_VERSION="${TALLOC_VERSION:-2.4.2}"
TALLOC_URL="${TALLOC_URL:-https://www.samba.org/ftp/talloc/talloc-${TALLOC_VERSION}.tar.gz}"
# VERIFY: SHA-256 for talloc-2.4.2.tar.gz; override if changing TALLOC_VERSION.
TALLOC_SHA256="${TALLOC_SHA256:-85ecf9e465e20f98f9950a52e9a411e14320bc555fa257d87697b7e7a9b1d8a6}"

API_LEVEL="${API_LEVEL:-28}"
NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK_DIR" ]] && [[ -n "${ANDROID_SDK_ROOT:-}" ]] && [[ -d "$ANDROID_SDK_ROOT/ndk" ]]; then
  NDK_DIR="$(ls -1d "$ANDROID_SDK_ROOT"/ndk/* 2>/dev/null | sort -V | tail -n 1 || true)"
fi
if [[ -z "$NDK_DIR" ]] || [[ ! -d "$NDK_DIR" ]]; then
  echo "ERROR: Set ANDROID_NDK_HOME or ANDROID_NDK_ROOT to an installed Android NDK." >&2
  exit 1
fi

HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"
if [[ "$HOST_TAG" == "linux-aarch64" ]]; then
  HOST_TAG="linux-arm64"
fi
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"
if [[ ! -d "$TOOLCHAIN" ]]; then
  TOOLCHAIN="$(ls -1d "$NDK_DIR"/toolchains/llvm/prebuilt/* | head -n 1)"
fi

export CC="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
export OBJDUMP="$TOOLCHAIN/bin/llvm-objdump"
export READELF="$TOOLCHAIN/bin/llvm-readelf"

if [[ ! -x "$CC" ]]; then
  echo "ERROR: NDK clang not found at $CC" >&2
  exit 1
fi

SYSROOT="$WORK_DIR/sysroot"
mkdir -p "$WORK_DIR" "$SYSROOT/include" "$SYSROOT/lib" "$OUT_DIR"

echo "==> Downloading & verifying talloc-${TALLOC_VERSION}.tar.gz..."
cd "$WORK_DIR"
TALLOC_TAR="talloc-${TALLOC_VERSION}.tar.gz"
if [[ ! -f "$TALLOC_TAR" ]]; then
  curl -fsSL "$TALLOC_URL" -o "$TALLOC_TAR"
fi
if [[ -n "$TALLOC_SHA256" ]]; then
  echo "${TALLOC_SHA256}  ${TALLOC_TAR}" | sha256sum -c -
else
  echo "WARN: TALLOC_SHA256 unset; VERIFY $(sha256sum "$TALLOC_TAR") manually."
fi

rm -rf "talloc-${TALLOC_VERSION}"
tar xzf "$TALLOC_TAR"
cd "talloc-${TALLOC_VERSION}"

# Cross-compile static libtalloc.a with NDK clang (avoids waf Android cross-exec checks)
"$CC" -O2 -fPIC -D_GNU_SOURCE -DTALLOC_BUILD_VERSION_MAJOR=2 -DTALLOC_BUILD_VERSION_MINOR=4 \
  -I. -Ilib/replace -c talloc.c -o talloc.o
"$AR" rcs "$SYSROOT/lib/libtalloc.a" talloc.o
cp -f talloc.h "$SYSROOT/include/"

echo "==> Downloading & building Termux PRoot (${PROOT_TAG})..."
cd "$WORK_DIR"
PROOT_TAR="proot-${PROOT_TAG}.tar.gz"
if [[ ! -f "$PROOT_TAR" ]]; then
  curl -fsSL "$PROOT_ARCHIVE_URL" -o "$PROOT_TAR"
fi
if [[ -n "$PROOT_TAR_SHA256" ]]; then
  echo "${PROOT_TAR_SHA256}  ${PROOT_TAR}" | sha256sum -c -
else
  echo "INFO: Downloaded ${PROOT_TAR} sha256=$(sha256sum "$PROOT_TAR" | awk '{print $1}') (VERIFY against GitHub tag ${PROOT_TAG})."
fi

rm -rf "proot-src"
mkdir -p "proot-src"
tar xzf "$PROOT_TAR" --strip-components=1 -C "proot-src"
cd "proot-src/src"

make -f GNUmakefile clean || true

# Build both proot and standalone loader with 16KB ELF page alignment (-Wl,-z,max-page-size=16384)
export CFLAGS="-O2 -fPIE -I${SYSROOT}/include -D_FILE_OFFSET_BITS=64 -DARG_MAX=131072 -DVERSION=\\\"${PROOT_TAG#v}\\\""
export LDFLAGS="-L${SYSROOT}/lib -ltalloc -Wl,-z,max-page-size=16384"
export PROOT_UNBUNDLE_LOADER="."

make -f GNUmakefile -j"$(nproc)" \
  CC="$CC" \
  LD="$CC" \
  STRIP="$STRIP" \
  OBJCOPY="$OBJCOPY" \
  OBJDUMP="$OBJDUMP" \
  V=1 \
  proot loader/loader

if [[ ! -f proot ]]; then
  echo "ERROR: proot binary was not produced." >&2
  exit 1
fi

LOADER_BIN=""
for candidate in loader/loader loader/loader-m32 loader; do
  if [[ -f "$candidate" ]]; then
    LOADER_BIN="$candidate"
    break
  fi
done
if [[ -z "$LOADER_BIN" ]]; then
  LOADER_BIN="$(find . -maxdepth 3 -type f -name 'loader*' -perm -u+x | head -n 1 || true)"
fi
if [[ -z "$LOADER_BIN" ]] || [[ ! -f "$LOADER_BIN" ]]; then
  echo "ERROR: proot loader binary not found. VERIFY Termux PRoot GNUmakefile loader target." >&2
  exit 1
fi

cp -f proot "$OUT_DIR/libproot.so"
cp -f "$LOADER_BIN" "$OUT_DIR/libproot-loader.so"
"$STRIP" --strip-unneeded "$OUT_DIR/libproot.so" "$OUT_DIR/libproot-loader.so" || true
chmod 755 "$OUT_DIR/libproot.so" "$OUT_DIR/libproot-loader.so"

echo "==> Success! Generated arm64-v8a binaries:"
ls -lh "$OUT_DIR/libproot.so" "$OUT_DIR/libproot-loader.so"
file "$OUT_DIR/libproot.so" "$OUT_DIR/libproot-loader.so" || true
if [[ -x "$READELF" ]]; then
  echo "==> ELF PT_LOAD alignment check:"
  "$READELF" -l "$OUT_DIR/libproot.so" | grep -E "LOAD|INTERP" || true
  "$READELF" -l "$OUT_DIR/libproot-loader.so" | grep -E "LOAD|INTERP" || true
fi

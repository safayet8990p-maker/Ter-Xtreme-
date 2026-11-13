# Ter Xtreme

Standalone Android (`arm64-v8a`) Linux terminal running **Alpine Linux (`aarch64`)** inside `proot`, with a custom NDK `forkpty` PTY layer and an offline-first `xterm.js` WebView UI. **No Termux installation or root access required.**

---

## Architecture

- **UI (`app/src/main/assets/` + Compose shell):** Edge-to-edge Jetpack Compose shell hosting a local-only `WebView` (`xterm.js` 5.5.0 + `addon-fit` 0.10.0 extracted from Maven WebJar assets at build time — zero CDN requests).
- **Keyboard & Extra Keys (`#keys`):** `position: fixed` bar tracked dynamically against `window.visualViewport` (`height` + `offsetTop`) so `#keys` stays docked cleanly above the soft keyboard without overlapping terminal lines. Supports sticky/locked `CTRL` and `ALT`, `ESC`, `TAB`, arrows, `HOME`/`END`/`PGUP`/`PGDN`, pinch-to-zoom, and native clipboard `Copy`/`Paste`.
- **PTY Layer (`app/src/main/cpp/terpty.c` + `PtySession.kt`):** Custom C JNI (`libterpty.so`) using Bionic `<pty.h>` (`openpty`, `fork`, `setsid`, `TIOCSCTTY`, `TIOCSWINSZ`, `poll`, `waitpid`) with 16ms output batching and Base64 bridging over `TermBridge`.
- **Linux Guest (`Installer.kt`, `NetSetup.kt`, `Launcher.kt`):**
  1. Verifies `libproot.so` and `libproot-loader.so` in `applicationInfo.nativeLibraryDir`.
  2. Fetches `latest-releases.yaml` from `dl-cdn.alpinelinux.org`, downloads `alpine-minirootfs-*-aarch64.tar.gz` with HTTP `Range` resume, verifies SHA256, and unpacks via Apache Commons Compress preserving symlinks and POSIX permissions.
  3. Writes `/etc/resolv.conf` (`1.1.1.1`, `8.8.8.8`), `/etc/hosts`, HTTPS Alpine repositories, `/usr/local/bin/pkg`, and runs initial `apk update && apk add --no-cache ca-certificates`.
  4. Spawns `proot --kill-on-exit --link2symlink -0 -r <rootfs> -b /dev -b /proc -b /sys -w /root /bin/sh -l`, with automatic `PROOT_NO_SECCOMP=1` retry if a device kernel blocks `ptrace`+seccomp.
- **Background Resilience (`TermService.kt`):** Runs an Android `specialUse` foreground service with a partial `WakeLock` and ongoing notification (**Stop** action) while PTY tabs are open.
- **Built-in Self-Test (`SelfTestRunner.kt`):** Interactive diagnostic screen running `uname -m`, `ping -c1 1.1.1.1`, `nslookup example.com`, `apk update`, `apk add --no-cache curl`, `curl -I https://example.com`, `wget`, and a real shell `not found` check.

---

## Android W^X & `targetSdk` Choice

- **`minSdk = 28` (Android 9)** through Android 17 devices; **`arm64-v8a` only**.
- **`targetSdk = 34` (Android 14)** chosen intentionally:
  - Android 10+ enforces W^X (`noexec` on app data directories), blocking direct `execve()` from `filesDir`. Ter Xtreme packages `libproot.so` and `libproot-loader.so` in `jniLibs/arm64-v8a/` with `android:extractNativeLibs="true"` and `useLegacyPackaging = true` so PackageManager extracts them into the executable `nativeLibraryDir`.
  - Android 15+ (`targetSdk >= 35`) enforces 16KB ELF segment alignment on 16KB-page devices. `libterpty.so` is already linked with `-Wl,-z,max-page-size=16384`. Once you build `libproot.so` and `libproot-loader.so` with `scripts/build_proot_arm64.sh` (which also passes `-Wl,-z,max-page-size=16384`) and verify on a 16KB emulator/device, you can safely raise `targetSdk = 35`. Keeping `targetSdk = 34` today avoids linker failures if a 4KB-aligned `proot` binary is supplied.

---

## Obtaining `libproot.so` and `libproot-loader.so`

Because compiled ELF binaries cannot be fabricated from text and `proot` is **GPLv2+**, build them directly from source:

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk
bash scripts/build_proot_arm64.sh
```

Or run the included GitHub Actions workflow (`.github/workflows/build.yml`), which builds `libproot.so` and `libproot-loader.so`, places them in `app/src/main/jniLibs/arm64-v8a/`, runs unit tests, and outputs the Debug APK.

---

## VERIFY Checklist Before Release

- **VERIFY Termux PRoot & talloc versions:** Check `PROOT_TAG` (currently pinned to `v5.1.107.102` from `https://github.com/termux/proot`) and `TALLOC_VERSION` / `TALLOC_SHA256` (`https://www.samba.org/ftp/talloc/`) in `scripts/build_proot_arm64.sh` before release builds.
- **Native Binary ELF Debug Screen:** Open **Native Binary Debug** from the Missing Binaries error screen or **Settings → Binary Debug** to verify file existence, byte sizes, `FLAG_EXTRACT_NATIVE_LIBS`, `PT_INTERP`, and 4KB vs 16KB ELF `PT_LOAD` alignment.
- **VERIFY `proot` flags on target hardware:** Confirm `--link2symlink` and `PROOT_NO_SECCOMP` behavior across your target Android 9–17 kernels (some OEM kernels require `PROOT_NO_SECCOMP=1`, which `ProotBackend` auto-enables on early exit).
- **VERIFY optional Ubuntu Base URL:** If adding Ubuntu Base `aarch64` later (`Installer.kt`), verify the exact release directory and `SHA256SUMS` signature at `https://cdimage.ubuntu.com/ubuntu-base/releases/`.

---

## Troubleshooting

1. **Processes killed in background on Android 12–14+ (Signal 9 / Phantom Process Killer):**
   - Android limits child processes (`max_phantom_processes`, default 32). Disable battery optimization for Ter Xtreme in Android Settings, or on Android 14+ enable **Developer Options → Disable child process restrictions**.
2. **Why doesn't `systemd` or Docker daemon run?**
   - `proot` intercepts userspace syscalls via `ptrace` without Linux kernel namespaces or `CAP_SYS_ADMIN`, so kernel init systems (`systemd`, `dockerd`) cannot boot inside unrooted `proot`. Standard CLI tools (`apk`, `git`, `python3`, `nodejs`, `openssh` client, `gcc`/`clang`, `curl`) work normally.
3. **DNS or HTTPS failures:**
   - Open **Self-Test** from the terminal header (`Test`) or Settings (`Run Self-Test`), and share the output with **Copy logs**.

# Third-Party Licenses & GPL Compliance

Ter Xtreme combines original app code (permissive) with open-source components, including `proot` (**GPLv2+**).

## Component License Summary

| Component | Purpose | License | Source / Notes |
| --- | --- | --- | --- |
| **Ter Xtreme App + `terpty` C JNI** | Kotlin UI, WebView bridge, `openpty`/`fork` PTY engine | Original Project Code | `app/src/main/java/`, `app/src/main/cpp/terpty.c` |
| **PRoot & Loader (`libproot.so`, `libproot-loader.so`)** | Rootless chroot/bind-mount syscall translation | **GPLv2 or later** | `https://github.com/termux/proot` (built by `scripts/build_proot_arm64.sh`) |
| **libtalloc** | Hierarchical memory allocator linked statically into `proot` | **LGPLv3 or later** | `https://www.samba.org/ftp/talloc/` |
| **Alpine Linux minirootfs** | Guest Linux userland downloaded at first run | **MIT / GPL / Various** | `https://alpinelinux.org/` (user-downloaded on first launch; not bundled in APK) |
| **xterm.js & `@xterm/addon-fit`** | VT100/xterm-256color terminal renderer | **MIT** | Bundled locally from Maven Central (`org.mvnpm.at.xterm`) |
| **OkHttp** | Resumable HTTPS downloads & manifest fetch | **Apache-2.0** | `com.squareup.okhttp3:okhttp` |
| **Apache Commons Compress** | `.tar.gz` extraction with symlinks & POSIX modes | **Apache-2.0** | `org.apache.commons:commons-compress` |
| **AndroidX (Compose, WebKit, DataStore)** | Native UI, WebView, and preference persistence | **Apache-2.0** | Google Android Jetpack |

---

## GPLv2+ Obligations When Distributing APKs with `proot`

If you distribute a compiled APK containing `libproot.so` and `libproot-loader.so` (`app/src/main/jniLibs/arm64-v8a/`):

1. Keep this `THIRD_PARTY_LICENSES.md` and the full GNU General Public License v2 text accessible to users.
2. Provide or keep publicly available the exact corresponding source code and build instructions (`scripts/build_proot_arm64.sh`) for the `proot` and `libtalloc` versions compiled into your APK, including the exact Termux `proot` commit SHA used for release builds.
3. Note why Ter Xtreme uses custom `terpty.c` instead of Termux's `terminal-emulator`: Termux's `terminal-emulator` module is **GPLv3**, whereas `terpty.c` is original C code using standard Bionic `<pty.h>` (`openpty`/`fork`) without GPLv3 linkage.

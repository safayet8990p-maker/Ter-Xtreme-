package com.example.term

object PtyNative {
  init {
    System.loadLibrary("terpty")
  }

  external fun createSubprocess(
    cmd: String,
    cwd: String,
    argv: Array<String>,
    envp: Array<String>,
    cols: Int,
    rows: Int,
  ): IntArray

  external fun readPty(
    masterFd: Int,
    buffer: ByteArray,
    offset: Int,
    length: Int,
  ): Int

  external fun writePty(
    masterFd: Int,
    bytes: ByteArray,
    offset: Int,
    length: Int,
  ): Int

  external fun setPtyWindowSize(
    masterFd: Int,
    cols: Int,
    rows: Int,
  ): Int

  external fun closeFd(fd: Int)

  external fun waitForProcess(
    pid: Int,
    noHang: Boolean,
  ): Int

  external fun killProcess(
    pid: Int,
    sig: Int,
  )
}

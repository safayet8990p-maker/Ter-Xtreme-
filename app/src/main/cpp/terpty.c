#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <poll.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static void throw_runtime(JNIEnv *env, const char *msg) {
  jclass cls = (*env)->FindClass(env, "java/lang/RuntimeException");
  if (cls != NULL) {
    (*env)->ThrowNew(env, cls, msg);
  }
}

static char **alloc_c_string_array(JNIEnv *env, jobjectArray jarr, int *out_len) {
  jsize len = jarr ? (*env)->GetArrayLength(env, jarr) : 0;
  char **arr = (char **)calloc((size_t)len + 1, sizeof(char *));
  if (!arr) return NULL;
  for (jsize i = 0; i < len; i++) {
    jstring jstr = (jstring)(*env)->GetObjectArrayElement(env, jarr, i);
    if (jstr) {
      const char *utf = (*env)->GetStringUTFChars(env, jstr, NULL);
      arr[i] = utf ? strdup(utf) : strdup("");
      if (utf) (*env)->ReleaseStringUTFChars(env, jstr, utf);
      (*env)->DeleteLocalRef(env, jstr);
    } else {
      arr[i] = strdup("");
    }
  }
  arr[len] = NULL;
  if (out_len) *out_len = (int)len;
  return arr;
}

static void free_c_string_array(char **arr, int len) {
  if (!arr) return;
  for (int i = 0; i < len; i++) {
    free(arr[i]);
  }
  free(arr);
}

static void close_inherited_fds(void) {
  DIR *dir = opendir("/proc/self/fd");
  if (dir) {
    int dir_fd = dirfd(dir);
    struct dirent *ent;
    while ((ent = readdir(dir)) != NULL) {
      char *endptr = NULL;
      long fd = strtol(ent->d_name, &endptr, 10);
      if (endptr && *endptr == '\0' && fd > 2 && fd != dir_fd) {
        close((int)fd);
      }
    }
    closedir(dir);
  } else {
    for (int fd = 3; fd < 1024; fd++) {
      close(fd);
    }
  }
}

JNIEXPORT jintArray JNICALL
Java_com_example_term_PtyNative_createSubprocess(
    JNIEnv *env,
    jobject thiz,
    jstring jcmd,
    jstring jcwd,
    jobjectArray jargv,
    jobjectArray jenvp,
    jint cols,
    jint rows) {
  (void)thiz;
  const char *cmd = (*env)->GetStringUTFChars(env, jcmd, NULL);
  const char *cwd = (*env)->GetStringUTFChars(env, jcwd, NULL);
  int argc = 0;
  int envc = 0;
  char **argv = alloc_c_string_array(env, jargv, &argc);
  char **envp = alloc_c_string_array(env, jenvp, &envc);

  if (!cmd || !cwd || !argv || !envp) {
    if (cmd) (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    free_c_string_array(argv, argc);
    free_c_string_array(envp, envc);
    throw_runtime(env, "Out of memory preparing subprocess");
    return NULL;
  }

  struct termios tio;
  memset(&tio, 0, sizeof(tio));
  tio.c_iflag = ICRNL | IXON | IUTF8;
  tio.c_oflag = OPOST | ONLCR;
  tio.c_cflag = CS8 | CREAD | HUPCL | B38400;
  tio.c_lflag = ISIG | ICANON | ECHO | ECHOE | ECHOK | IEXTEN;
  tio.c_cc[VINTR] = 3;
  tio.c_cc[VQUIT] = 28;
  tio.c_cc[VERASE] = 127;
  tio.c_cc[VKILL] = 21;
  tio.c_cc[VEOF] = 4;
  tio.c_cc[VTIME] = 0;
  tio.c_cc[VMIN] = 1;
  tio.c_cc[VSTART] = 17;
  tio.c_cc[VSTOP] = 19;
  tio.c_cc[VSUSP] = 26;
  tio.c_cc[VWERASE] = 23;
  tio.c_cc[VLNEXT] = 22;

  struct winsize ws;
  memset(&ws, 0, sizeof(ws));
  ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
  ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);

  int master_fd = -1;
  int slave_fd = -1;
  if (openpty(&master_fd, &slave_fd, NULL, &tio, &ws) != 0) {
    char errbuf[256];
    snprintf(errbuf, sizeof(errbuf), "openpty failed: %s", strerror(errno));
    (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    free_c_string_array(argv, argc);
    free_c_string_array(envp, envc);
    throw_runtime(env, errbuf);
    return NULL;
  }

  int flags = fcntl(master_fd, F_GETFD);
  if (flags != -1) {
    fcntl(master_fd, F_SETFD, flags | FD_CLOEXEC);
  }

  pid_t pid = fork();
  if (pid < 0) {
    int saved_errno = errno;
    close(master_fd);
    close(slave_fd);
    char errbuf[256];
    snprintf(errbuf, sizeof(errbuf), "fork failed: %s", strerror(saved_errno));
    (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    free_c_string_array(argv, argc);
    free_c_string_array(envp, envc);
    throw_runtime(env, errbuf);
    return NULL;
  }

  if (pid == 0) {
    sigset_t signals_to_unblock;
    sigfillset(&signals_to_unblock);
    sigprocmask(SIG_UNBLOCK, &signals_to_unblock, NULL);

    close(master_fd);
    setsid();
    ioctl(slave_fd, TIOCSCTTY, 0);

    dup2(slave_fd, STDIN_FILENO);
    dup2(slave_fd, STDOUT_FILENO);
    dup2(slave_fd, STDERR_FILENO);
    if (slave_fd > STDERR_FILENO) {
      close(slave_fd);
    }

    close_inherited_fds();
    if (chdir(cwd) != 0) {
      // Fallback to root if working directory does not exist yet
      chdir("/");
    }

    execve(cmd, argv, envp);
    _exit(127);
  }

  close(slave_fd);
  (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
  (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
  free_c_string_array(argv, argc);
  free_c_string_array(envp, envc);

  jintArray result = (*env)->NewIntArray(env, 2);
  if (!result) {
    close(master_fd);
    return NULL;
  }
  jint vals[2] = {(jint)pid, (jint)master_fd};
  (*env)->SetIntArrayRegion(env, result, 0, 2, vals);
  return result;
}

JNIEXPORT jint JNICALL
Java_com_example_term_PtyNative_readPty(
    JNIEnv *env,
    jobject thiz,
    jint master_fd,
    jbyteArray buffer,
    jint offset,
    jint length) {
  (void)thiz;
  if (master_fd < 0 || !buffer || length <= 0) return -1;

  struct pollfd pfd;
  pfd.fd = master_fd;
  pfd.events = POLLIN;
  pfd.revents = 0;

  int pr = poll(&pfd, 1, 50);
  if (pr == 0) return 0; // Timeout, keep caller responsive
  if (pr < 0) {
    if (errno == EINTR) return 0;
    return -1;
  }

  if ((pfd.revents & POLLIN) == 0) {
    if (pfd.revents & (POLLHUP | POLLERR | POLLNVAL)) return -1;
    return 0;
  }

  jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
  if (!buf) return -1;
  ssize_t n = read(master_fd, buf + offset, (size_t)length);
  int saved_errno = errno;
  (*env)->ReleaseByteArrayElements(env, buffer, buf, 0);

  if (n < 0) {
    if (saved_errno == EINTR || saved_errno == EAGAIN) return 0;
    return -1;
  }
  if (n == 0) return -1;
  return (jint)n;
}

JNIEXPORT jint JNICALL
Java_com_example_term_PtyNative_writePty(
    JNIEnv *env,
    jobject thiz,
    jint master_fd,
    jbyteArray bytes,
    jint offset,
    jint length) {
  (void)thiz;
  if (master_fd < 0 || !bytes || length <= 0) return 0;
  jbyte *buf = (*env)->GetByteArrayElements(env, bytes, NULL);
  if (!buf) return -1;

  int written_total = 0;
  while (written_total < length) {
    ssize_t n = write(master_fd, buf + offset + written_total, (size_t)(length - written_total));
    if (n > 0) {
      written_total += (int)n;
    } else if (n < 0 && errno == EINTR) {
      continue;
    } else {
      (*env)->ReleaseByteArrayElements(env, bytes, buf, JNI_ABORT);
      return -1;
    }
  }
  (*env)->ReleaseByteArrayElements(env, bytes, buf, JNI_ABORT);
  return written_total;
}

JNIEXPORT jint JNICALL
Java_com_example_term_PtyNative_setPtyWindowSize(
    JNIEnv *env,
    jobject thiz,
    jint master_fd,
    jint cols,
    jint rows) {
  (void)env;
  (void)thiz;
  if (master_fd < 0) return -1;
  struct winsize ws;
  memset(&ws, 0, sizeof(ws));
  ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);
  ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
  return ioctl(master_fd, TIOCSWINSZ, &ws) == 0 ? 0 : -1;
}

JNIEXPORT void JNICALL
Java_com_example_term_PtyNative_closeFd(JNIEnv *env, jobject thiz, jint fd) {
  (void)env;
  (void)thiz;
  if (fd >= 0) close(fd);
}

JNIEXPORT jint JNICALL
Java_com_example_term_PtyNative_waitForProcess(
    JNIEnv *env,
    jobject thiz,
    jint pid,
    jboolean no_hang) {
  (void)env;
  (void)thiz;
  if (pid <= 0) return -1;
  int status = 0;
  pid_t w;
  do {
    w = waitpid((pid_t)pid, &status, no_hang ? WNOHANG : 0);
  } while (w < 0 && errno == EINTR);

  if (w == 0) return -2; // Still running
  if (w < 0) return -1;
  if (WIFEXITED(status)) return WEXITSTATUS(status);
  if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
  return 1;
}

JNIEXPORT void JNICALL
Java_com_example_term_PtyNative_killProcess(
    JNIEnv *env,
    jobject thiz,
    jint pid,
    jint sig) {
  (void)env;
  (void)thiz;
  if (pid <= 0) return;
  kill(-(pid_t)pid, sig);
  kill((pid_t)pid, sig);
}

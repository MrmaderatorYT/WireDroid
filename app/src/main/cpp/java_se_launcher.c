#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define TAG "JavaSeLauncher"

/*
 * Write end of the pipe standing in for the JVM's stdin.
 *
 * The JVM runs inside this process through JLI_Launch, so its System.in is a
 * real file descriptor 0 — replacing the stream from Java would change nothing.
 * The launcher hands descriptor 0 a pipe and keeps the other end here, which is
 * what the console's input field writes into.
 *
 * Written from a different thread than the one blocked in JLI_Launch, so it is
 * volatile and every use re-reads it.
 */
static volatile int g_stdin_write_fd = -1;
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef jint JLI_Launch_func(
        int argc, char **argv,
        int jargc, const char **jargv,
        int appclassc, const char **appclassv,
        const char *fullversion, const char *dotversion,
        const char *pname, const char *lname,
        jboolean javaargs, jboolean cpwildcard, jboolean javaw, jint ergo);

typedef void (*update_ld_path_func)(char *path);

static void set_android_library_path(char *path) {
    void *libdl = dlopen("libdl.so", RTLD_NOW);
    if (libdl == NULL) return;
    update_ld_path_func update =
            (update_ld_path_func) dlsym(libdl, "android_update_LD_LIBRARY_PATH");
    if (update == NULL) {
        update = (update_ld_path_func) dlsym(
                libdl, "__loader_android_update_LD_LIBRARY_PATH");
    }
    if (update != NULL) update(path);
    dlclose(libdl);
}

static void *open_global(const char *path, int required) {
    dlerror();
    void *handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (handle == NULL) {
        const char *error = dlerror();
        if (required) {
            dprintf(STDERR_FILENO, "Java SE: dlopen %s failed: %s\n",
                    path, error != NULL ? error : "unknown error");
        } else {
            LOGE("Optional dlopen %s failed: %s", path,
                    error != NULL ? error : "unknown error");
        }
    }
    return handle;
}

static void preload_directory(const char *directory, int depth) {
    if (depth > 3) return;
    DIR *dir = opendir(directory);
    if (dir == NULL) return;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
        char path[PATH_MAX];
        if (snprintf(path, sizeof(path), "%s/%s", directory, entry->d_name)
                >= (int) sizeof(path)) continue;
        if (entry->d_type == DT_DIR) {
            preload_directory(path, depth + 1);
        } else {
            size_t length = strlen(entry->d_name);
            if (length > 3 && strcmp(entry->d_name + length - 3, ".so") == 0) {
                open_global(path, 0);
            }
        }
    }
    closedir(dir);
}

static void reset_signal_handlers(void) {
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    sigemptyset(&action.sa_mask);
    for (int signal_number = SIGHUP; signal_number < NSIG; signal_number++) {
        if (signal_number == SIGKILL || signal_number == SIGSTOP) continue;
        action.sa_handler = signal_number == SIGSEGV ? SIG_IGN : SIG_DFL;
        sigaction(signal_number, &action, NULL);
    }
}

static char **to_argv(JNIEnv *env, jobjectArray array, int *length_out) {
    int length = (*env)->GetArrayLength(env, array);
    char **result = calloc((size_t) length + 1, sizeof(char *));
    if (result == NULL) return NULL;
    for (int i = 0; i < length; i++) {
        jstring value = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        result[i] = utf != NULL ? strdup(utf) : NULL;
        if (utf != NULL) (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
        if (result[i] == NULL) {
            for (int j = 0; j < i; j++) free(result[j]);
            free(result);
            return NULL;
        }
    }
    *length_out = length;
    return result;
}

static void free_argv(char **argv, int length) {
    if (argv == NULL) return;
    for (int i = 0; i < length; i++) free(argv[i]);
    free(argv);
}

static void apply_environment(JNIEnv *env, jobjectArray pairs) {
    if (pairs == NULL) return;
    int length = (*env)->GetArrayLength(env, pairs);
    for (int i = 0; i < length; i++) {
        jstring value = (jstring) (*env)->GetObjectArrayElement(env, pairs, i);
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        if (utf != NULL) {
            char *copy = strdup(utf);
            char *equals = copy != NULL ? strchr(copy, '=') : NULL;
            if (equals != NULL && equals != copy) {
                *equals = '\0';
                setenv(copy, equals + 1, 1);
            }
            free(copy);
            (*env)->ReleaseStringUTFChars(env, value, utf);
        }
        (*env)->DeleteLocalRef(env, value);
    }
}

JNIEXPORT jint JNICALL
Java_com_ccs_wiredroid_javase_JavaSeNativeLauncher_launch(
        JNIEnv *env, jclass clazz, jstring runtime_home_string,
        jstring working_directory_string, jobjectArray arguments,
        jobjectArray environment, jstring output_path_string) {
    (void) clazz;
    if (runtime_home_string == NULL || working_directory_string == NULL
            || arguments == NULL || output_path_string == NULL) return -10;

    const char *runtime_home = (*env)->GetStringUTFChars(env, runtime_home_string, NULL);
    const char *working_directory =
            (*env)->GetStringUTFChars(env, working_directory_string, NULL);
    const char *output_path = (*env)->GetStringUTFChars(env, output_path_string, NULL);
    if (runtime_home == NULL || working_directory == NULL || output_path == NULL) return -11;

    int output = open(output_path, O_CREAT | O_WRONLY | O_TRUNC, 0600);
    if (output < 0) {
        LOGE("Could not open JVM output file %s", output_path);
        return -12;
    }
    fflush(stdout);
    fflush(stderr);
    int old_stdout = dup(STDOUT_FILENO);
    int old_stderr = dup(STDERR_FILENO);
    dup2(output, STDOUT_FILENO);
    dup2(output, STDERR_FILENO);
    close(output);
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IOLBF, 0);

    int old_stdin = dup(STDIN_FILENO);
    int stdin_pipe[2];
    if (pipe(stdin_pipe) == 0) {
        dup2(stdin_pipe[0], STDIN_FILENO);
        close(stdin_pipe[0]);
        g_stdin_write_fd = stdin_pipe[1];
    } else {
        /* Without a pipe the JVM inherits the app's own stdin, which is empty —
         * the same broken read the pipe exists to fix, but not a reason to
         * refuse to run the program. */
        LOGE("Could not create the stdin pipe; input will be unavailable");
    }

    apply_environment(env, environment);

    char lib_dir[PATH_MAX];
    char vm_dir[PATH_MAX];
    char library_path[PATH_MAX * 2];
    char executable_path[PATH_MAX * 2];
    snprintf(lib_dir, sizeof(lib_dir), "%s/lib", runtime_home);
    snprintf(vm_dir, sizeof(vm_dir), "%s/lib/server", runtime_home);
    if (access(vm_dir, F_OK) != 0) snprintf(vm_dir, sizeof(vm_dir), "%s/lib/client", runtime_home);
    snprintf(library_path, sizeof(library_path), "%s:%s", vm_dir, lib_dir);
    const char *old_path = getenv("PATH");
    snprintf(executable_path, sizeof(executable_path), "%s/bin:%s", runtime_home,
            old_path != NULL ? old_path : "/system/bin");
    setenv("JAVA_HOME", runtime_home, 1);
    setenv("HOME", working_directory, 1);
    setenv("PATH", executable_path, 1);
    setenv("LD_LIBRARY_PATH", library_path, 1);
    set_android_library_path(library_path);
    if (chdir(working_directory) != 0) {
        dprintf(STDERR_FILENO, "Java SE: cannot use working directory %s\n", working_directory);
    }

    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/lib/libjli.so", runtime_home);
    void *jli = open_global(path, 1);
    snprintf(path, sizeof(path), "%s/lib/server/libjvm.so", runtime_home);
    if (access(path, F_OK) != 0) snprintf(path, sizeof(path), "%s/lib/client/libjvm.so", runtime_home);
    void *jvm = open_global(path, 1);

    const char *core_libraries[] = {
            "libverify.so", "libjava.so", "libnet.so", "libnio.so",
            "libawt.so", "libawt_headless.so", "libfreetype.so", "libfontmanager.so"
    };
    for (size_t i = 0; i < sizeof(core_libraries) / sizeof(core_libraries[0]); i++) {
        snprintf(path, sizeof(path), "%s/lib/%s", runtime_home, core_libraries[i]);
        open_global(path, 0);
    }
    preload_directory(lib_dir, 0);

    int argc = 0;
    char **argv = to_argv(env, arguments, &argc);
    jint result = -13;
    if (jli != NULL && jvm != NULL && argv != NULL && argc > 0) {
        JLI_Launch_func *launch = (JLI_Launch_func *) dlsym(jli, "JLI_Launch");
        if (launch == NULL) {
            dprintf(STDERR_FILENO, "Java SE: JLI_Launch was not found\n");
            result = -14;
        } else {
            reset_signal_handlers();
            result = launch(argc, argv, 0, NULL, 0, NULL,
                    "21.0.1", "21", argv[0], argv[0],
                    JNI_FALSE, JNI_TRUE, JNI_FALSE, 0);
        }
    }

    free_argv(argv, argc);
    fflush(stdout);
    fflush(stderr);
    if (old_stdout >= 0) {
        dup2(old_stdout, STDOUT_FILENO);
        close(old_stdout);
    }
    if (old_stderr >= 0) {
        dup2(old_stderr, STDERR_FILENO);
        close(old_stderr);
    }
    int stdin_fd = g_stdin_write_fd;
    g_stdin_write_fd = -1;
    if (stdin_fd >= 0) close(stdin_fd);
    if (old_stdin >= 0) {
        dup2(old_stdin, STDIN_FILENO);
        close(old_stdin);
    }
    (*env)->ReleaseStringUTFChars(env, runtime_home_string, runtime_home);
    (*env)->ReleaseStringUTFChars(env, working_directory_string, working_directory);
    (*env)->ReleaseStringUTFChars(env, output_path_string, output_path);
    return result;
}

/*
 * Hands the running program a chunk of input.
 *
 * Returns the number of bytes written, or -1 when nothing is running. Partial
 * writes are looped over: a pipe accepts only what fits in its buffer, and a
 * short write would silently drop the rest of the user's line.
 */
JNIEXPORT jint JNICALL
Java_com_ccs_wiredroid_javase_JavaSeNativeLauncher_writeStdin(
        JNIEnv *env, jclass clazz, jbyteArray data) {
    (void) clazz;
    int fd = g_stdin_write_fd;
    if (fd < 0 || data == NULL) return -1;

    jsize length = (*env)->GetArrayLength(env, data);
    if (length <= 0) return 0;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) return -1;

    jsize written = 0;
    while (written < length) {
        ssize_t n = write(fd, bytes + written, (size_t) (length - written));
        if (n <= 0) break;
        written += (jsize) n;
    }
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return written;
}

/*
 * Reports end of input, so a program blocked on read() sees EOF instead of
 * waiting for a line that is never coming.
 */
JNIEXPORT void JNICALL
Java_com_ccs_wiredroid_javase_JavaSeNativeLauncher_closeStdin(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    int fd = g_stdin_write_fd;
    g_stdin_write_fd = -1;
    if (fd >= 0) close(fd);
}

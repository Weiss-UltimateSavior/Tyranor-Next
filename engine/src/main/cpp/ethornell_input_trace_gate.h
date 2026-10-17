#ifndef ETHORNELL_INPUT_TRACE_GATE_H
#define ETHORNELL_INPUT_TRACE_GATE_H
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace ethornell_bridge {
inline bool inputTraceApproved(const char* privateFiles) {
#ifdef NDEBUG
    (void) privateFiles;
    return false;
#else
    if (!privateFiles || privateFiles[0] != '/') return false;
    int files = open(privateFiles, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (files < 0) return false;
    struct stat info{};
    // Android may give filesDir app-group write permission; the metadata subdirectory
    // and sentinel below must still be owner-only writable.
    bool valid = fstat(files, &info) == 0 && info.st_uid == getuid() && (info.st_mode & 0002) == 0;
    int metadata = valid ? openat(files, "ethornell_debug", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC) : -1;
    close(files);
    if (metadata < 0) return false;
    valid = fstat(metadata, &info) == 0 && info.st_uid == getuid() && (info.st_mode & 0022) == 0;
    int sentinel = valid ? openat(metadata, ".ethornell-input-trace", O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC) : -1;
    close(metadata);
    if (sentinel < 0) return false;
    valid = fstat(sentinel, &info) == 0 && S_ISREG(info.st_mode) && info.st_uid == getuid()
        && info.st_nlink == 1 && info.st_size == 0 && (info.st_mode & 0022) == 0;
    close(sentinel);
    return valid;
#endif
}
}
#endif

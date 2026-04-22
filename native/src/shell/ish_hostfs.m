#import <Foundation/Foundation.h>

#include "shell/ish_hostfs.h"

#include <TargetConditionals.h>
#include <errno.h>
#include <mach/mach.h>
#include <mach-o/dyld.h>
#include <mach-o/loader.h>
#include <stdbool.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/sysctl.h>
#include <sys/time.h>
#include <sys/utsname.h>
#include <unistd.h>

#include "fs/fd.h"
#include "fs/path.h"
#include "kernel/calls.h"
#include "kernel/errno.h"
#include "kernel/fs.h"

typedef enum mira_hostfs_node {
    MIRA_HOSTFS_NODE_ROOT = 1,
    MIRA_HOSTFS_NODE_SUMMARY,
    MIRA_HOSTFS_NODE_PATHS,
    MIRA_HOSTFS_NODE_BUNDLE,
    MIRA_HOSTFS_NODE_IMAGES,
    MIRA_HOSTFS_NODE_MAPS,
    MIRA_HOSTFS_NODE_TASK,
} mira_hostfs_node_t;

typedef struct mira_hostfs_buffer {
    char *data;
    size_t length;
    size_t capacity;
} mira_hostfs_buffer_t;

typedef struct mira_hostfs_fd {
    mira_hostfs_node_t node;
    char *data;
    size_t size;
} mira_hostfs_fd_t;

typedef struct mira_hostfs_entry {
    const char *name;
    mira_hostfs_node_t node;
} mira_hostfs_entry_t;

static const mira_hostfs_entry_t g_mira_hostfs_entries[] = {
    {"summary", MIRA_HOSTFS_NODE_SUMMARY},
    {"paths", MIRA_HOSTFS_NODE_PATHS},
    {"bundle", MIRA_HOSTFS_NODE_BUNDLE},
    {"images", MIRA_HOSTFS_NODE_IMAGES},
    {"maps", MIRA_HOSTFS_NODE_MAPS},
    {"task", MIRA_HOSTFS_NODE_TASK},
};

static int mira_hostfs_buffer_append(mira_hostfs_buffer_t *buffer, const void *data, size_t length) {
    if (length == 0) {
        return 0;
    }
    size_t needed = buffer->length + length + 1U;
    if (needed > buffer->capacity) {
        size_t next = buffer->capacity == 0 ? 4096U : buffer->capacity;
        while (next < needed) {
            next *= 2U;
        }
        char *resized = (char *) realloc(buffer->data, next);
        if (resized == NULL) {
            return _ENOMEM;
        }
        buffer->data = resized;
        buffer->capacity = next;
    }
    memcpy(buffer->data + buffer->length, data, length);
    buffer->length += length;
    buffer->data[buffer->length] = '\0';
    return 0;
}

static int mira_hostfs_buffer_appendf(mira_hostfs_buffer_t *buffer, const char *format, ...) {
    va_list args;
    va_start(args, format);
    va_list copy;
    va_copy(copy, args);
    int needed = vsnprintf(NULL, 0, format, args);
    va_end(args);
    if (needed < 0) {
        va_end(copy);
        return _EIO;
    }
    char *scratch = (char *) malloc((size_t) needed + 1U);
    if (scratch == NULL) {
        va_end(copy);
        return _ENOMEM;
    }
    vsnprintf(scratch, (size_t) needed + 1U, format, copy);
    va_end(copy);
    int err = mira_hostfs_buffer_append(buffer, scratch, (size_t) needed);
    free(scratch);
    return err;
}

static const char *mira_hostfs_node_name(mira_hostfs_node_t node) {
    if (node == MIRA_HOSTFS_NODE_ROOT) {
        return "";
    }
    for (size_t i = 0; i < sizeof(g_mira_hostfs_entries) / sizeof(g_mira_hostfs_entries[0]); ++i) {
        if (g_mira_hostfs_entries[i].node == node) {
            return g_mira_hostfs_entries[i].name;
        }
    }
    return "unknown";
}

static bool mira_hostfs_lookup_node(const char *path, mira_hostfs_node_t *node) {
    if (path == NULL || path[0] == '\0' || strcmp(path, "/") == 0) {
        *node = MIRA_HOSTFS_NODE_ROOT;
        return true;
    }
    if (path[0] == '/') {
        path++;
    }
    if (strchr(path, '/') != NULL) {
        return false;
    }
    for (size_t i = 0; i < sizeof(g_mira_hostfs_entries) / sizeof(g_mira_hostfs_entries[0]); ++i) {
        if (strcmp(path, g_mira_hostfs_entries[i].name) == 0) {
            *node = g_mira_hostfs_entries[i].node;
            return true;
        }
    }
    return false;
}

static uint64_t mira_hostfs_inode_for_node(mira_hostfs_node_t node) {
    return 0x6d6972610000ULL + (uint64_t) node;
}

static char mira_hostfs_prot_char(vm_prot_t prot, vm_prot_t mask, char value) {
    return (prot & mask) ? value : '-';
}

static bool mira_hostfs_header_is_64(const struct mach_header *header) {
    return header != NULL && (header->magic == MH_MAGIC_64 || header->magic == MH_CIGAM_64);
}

static const char *mira_hostfs_image_name_for_address(uintptr_t address, const char **segment_out) {
    uint32_t count = _dyld_image_count();
    for (uint32_t i = 0; i < count; ++i) {
        const struct mach_header *header = _dyld_get_image_header(i);
        if (!mira_hostfs_header_is_64(header)) {
            continue;
        }
        const struct mach_header_64 *header64 = (const struct mach_header_64 *) header;
        intptr_t slide = _dyld_get_image_vmaddr_slide(i);
        const uint8_t *cursor = (const uint8_t *) (header64 + 1);
        for (uint32_t command_index = 0; command_index < header64->ncmds; ++command_index) {
            const struct load_command *command = (const struct load_command *) cursor;
            if (command->cmd == LC_SEGMENT_64) {
                const struct segment_command_64 *segment = (const struct segment_command_64 *) command;
                uintptr_t start = (uintptr_t) ((uint64_t) segment->vmaddr + (uint64_t) slide);
                uintptr_t end = start + (uintptr_t) segment->vmsize;
                if (address >= start && address < end) {
                    if (segment_out != NULL) {
                        *segment_out = segment->segname;
                    }
                    return _dyld_get_image_name(i);
                }
            }
            cursor += command->cmdsize;
        }
    }
    if (segment_out != NULL) {
        *segment_out = "";
    }
    return "";
}

static int mira_hostfs_append_summary(mira_hostfs_buffer_t *buffer) {
    struct utsname uts = {};
    uname(&uts);
    int err = mira_hostfs_buffer_appendf(buffer,
        "name=Mira host introspection\n"
        "scope=current iOS app process and app-accessible paths only\n"
        "pid=%d\n"
        "uid=%d\n"
        "gid=%d\n"
        "os=%s %s %s\n"
        "machine=%s\n"
        "dyld_image_count=%u\n",
        getpid(),
        getuid(),
        getgid(),
        uts.sysname,
        uts.release,
        uts.version,
        uts.machine,
        _dyld_image_count());
    if (err < 0) {
        return err;
    }
    return mira_hostfs_buffer_append(buffer,
        "\nfiles:\n"
        "  /mira/host/summary  overview\n"
        "  /mira/host/paths    app bundle and sandbox paths\n"
        "  /mira/host/bundle   main bundle metadata\n"
        "  /mira/host/images   dyld Mach-O image list for this process\n"
        "  /mira/host/maps     Mach VM regions for this process\n"
        "  /mira/host/task     Mach task memory counters for this process\n",
        strlen("\nfiles:\n"
               "  /mira/host/summary  overview\n"
               "  /mira/host/paths    app bundle and sandbox paths\n"
               "  /mira/host/bundle   main bundle metadata\n"
               "  /mira/host/images   dyld Mach-O image list for this process\n"
               "  /mira/host/maps     Mach VM regions for this process\n"
               "  /mira/host/task     Mach task memory counters for this process\n"));
}

static int mira_hostfs_append_paths(mira_hostfs_buffer_t *buffer) {
    @autoreleasepool {
        NSFileManager *manager = NSFileManager.defaultManager;
        NSArray<NSURL *> *documents = [manager URLsForDirectory:NSDocumentDirectory inDomains:NSUserDomainMask];
        NSArray<NSURL *> *library = [manager URLsForDirectory:NSLibraryDirectory inDomains:NSUserDomainMask];
        NSArray<NSURL *> *applicationSupport = [manager URLsForDirectory:NSApplicationSupportDirectory inDomains:NSUserDomainMask];
        NSArray<NSURL *> *caches = [manager URLsForDirectory:NSCachesDirectory inDomains:NSUserDomainMask];

        int err = mira_hostfs_buffer_appendf(buffer, "home=%s\n", NSHomeDirectory().fileSystemRepresentation);
        if (err < 0) return err;
        err = mira_hostfs_buffer_appendf(buffer, "tmp=%s\n", NSTemporaryDirectory().fileSystemRepresentation);
        if (err < 0) return err;
        err = mira_hostfs_buffer_appendf(buffer, "bundle=%s\n", NSBundle.mainBundle.bundleURL.fileSystemRepresentation);
        if (err < 0) return err;
        err = mira_hostfs_buffer_appendf(buffer, "executable=%s\n", NSBundle.mainBundle.executableURL.fileSystemRepresentation);
        if (err < 0) return err;
        err = mira_hostfs_buffer_appendf(buffer, "documents=%s\n", documents.firstObject.fileSystemRepresentation ?: "");
        if (err < 0) return err;
        err = mira_hostfs_buffer_appendf(buffer, "library=%s\n", library.firstObject.fileSystemRepresentation ?: "");
        if (err < 0) return err;
        err = mira_hostfs_buffer_appendf(buffer, "application_support=%s\n", applicationSupport.firstObject.fileSystemRepresentation ?: "");
        if (err < 0) return err;
        err = mira_hostfs_buffer_appendf(buffer, "caches=%s\n", caches.firstObject.fileSystemRepresentation ?: "");
        if (err < 0) return err;
        NSURL *ishRoot = [[[applicationSupport.firstObject URLByAppendingPathComponent:@"Mira" isDirectory:YES]
                           URLByAppendingPathComponent:@"iSH" isDirectory:YES]
                          URLByAppendingPathComponent:@"default" isDirectory:YES];
        return mira_hostfs_buffer_appendf(buffer, "ish_root=%s\n", ishRoot.fileSystemRepresentation ?: "");
    }
}

static int mira_hostfs_append_bundle(mira_hostfs_buffer_t *buffer) {
    @autoreleasepool {
        NSBundle *bundle = NSBundle.mainBundle;
        NSDictionary *info = bundle.infoDictionary;
        int err = mira_hostfs_buffer_appendf(buffer, "bundle_path=%s\n", bundle.bundleURL.fileSystemRepresentation);
        if (err < 0) return err;
        err = mira_hostfs_buffer_appendf(buffer, "executable_path=%s\n", bundle.executableURL.fileSystemRepresentation);
        if (err < 0) return err;
        err = mira_hostfs_buffer_appendf(buffer, "identifier=%s\n", bundle.bundleIdentifier.UTF8String ?: "");
        if (err < 0) return err;
        err = mira_hostfs_buffer_appendf(buffer, "name=%s\n", [info[@"CFBundleName"] description].UTF8String ?: "");
        if (err < 0) return err;
        err = mira_hostfs_buffer_appendf(buffer, "display_name=%s\n", [info[@"CFBundleDisplayName"] description].UTF8String ?: "");
        if (err < 0) return err;
        err = mira_hostfs_buffer_appendf(buffer, "version=%s\n", [info[@"CFBundleShortVersionString"] description].UTF8String ?: "");
        if (err < 0) return err;
        err = mira_hostfs_buffer_appendf(buffer, "build=%s\n", [info[@"CFBundleVersion"] description].UTF8String ?: "");
        if (err < 0) return err;
        err = mira_hostfs_buffer_appendf(buffer, "minimum_os=%s\n", [info[@"MinimumOSVersion"] description].UTF8String ?: "");
        if (err < 0) return err;
        err = mira_hostfs_buffer_appendf(buffer, "embedded_provision=%s\n", [bundle pathForResource:@"embedded" ofType:@"mobileprovision"].UTF8String ?: "");
        if (err < 0) return err;

        NSError *error = nil;
        NSArray<NSString *> *items = [NSFileManager.defaultManager contentsOfDirectoryAtPath:bundle.bundlePath error:&error];
        if (items != nil) {
            err = mira_hostfs_buffer_append(buffer, "\nroot_entries:\n", strlen("\nroot_entries:\n"));
            if (err < 0) return err;
            for (NSString *item in [items sortedArrayUsingSelector:@selector(compare:)]) {
                err = mira_hostfs_buffer_appendf(buffer, "%s\n", item.UTF8String);
                if (err < 0) return err;
            }
        } else {
            err = mira_hostfs_buffer_appendf(buffer, "\nroot_entries_error=%s\n", error.localizedDescription.UTF8String ?: "unknown");
            if (err < 0) return err;
        }
    }
    return 0;
}

static int mira_hostfs_append_images(mira_hostfs_buffer_t *buffer) {
    uint32_t count = _dyld_image_count();
    int err = mira_hostfs_buffer_appendf(buffer, "count=%u\n", count);
    if (err < 0) {
        return err;
    }
    for (uint32_t i = 0; i < count; ++i) {
        const struct mach_header *header = _dyld_get_image_header(i);
        intptr_t slide = _dyld_get_image_vmaddr_slide(i);
        const char *name = _dyld_get_image_name(i);
        err = mira_hostfs_buffer_appendf(buffer,
            "%4u 0x%016llx slide=%+lld magic=0x%08x %s\n",
            i,
            (unsigned long long) (uintptr_t) header,
            (long long) slide,
            header == NULL ? 0 : header->magic,
            name == NULL ? "" : name);
        if (err < 0) {
            return err;
        }
    }
    return 0;
}

static int mira_hostfs_append_maps(mira_hostfs_buffer_t *buffer) {
    mach_port_t task = mach_task_self();
    vm_address_t address = 0;
    natural_t depth = 0;
    int err = mira_hostfs_buffer_append(buffer,
        "start-end              prot maxp share tag depth segment image\n",
        strlen("start-end              prot maxp share tag depth segment image\n"));
    if (err < 0) {
        return err;
    }

    for (unsigned region_count = 0; region_count < 20000; ++region_count) {
        vm_size_t size = 0;
        vm_region_submap_info_data_64_t info = {};
        mach_msg_type_number_t count = VM_REGION_SUBMAP_INFO_COUNT_64;
        kern_return_t kr = vm_region_recurse_64(task, &address, &size, &depth, (vm_region_recurse_info_t) &info, &count);
        if (kr != KERN_SUCCESS) {
            break;
        }
        if (info.is_submap) {
            depth++;
            continue;
        }

        const char *segment = "";
        const char *image = mira_hostfs_image_name_for_address((uintptr_t) address, &segment);
        vm_address_t end = address + size;
        err = mira_hostfs_buffer_appendf(buffer,
            "0x%016llx-0x%016llx %c%c%c %c%c%c %5u %3u %5u %-16s %s\n",
            (unsigned long long) address,
            (unsigned long long) end,
            mira_hostfs_prot_char(info.protection, VM_PROT_READ, 'r'),
            mira_hostfs_prot_char(info.protection, VM_PROT_WRITE, 'w'),
            mira_hostfs_prot_char(info.protection, VM_PROT_EXECUTE, 'x'),
            mira_hostfs_prot_char(info.max_protection, VM_PROT_READ, 'r'),
            mira_hostfs_prot_char(info.max_protection, VM_PROT_WRITE, 'w'),
            mira_hostfs_prot_char(info.max_protection, VM_PROT_EXECUTE, 'x'),
            info.share_mode,
            info.user_tag,
            depth,
            segment == NULL ? "" : segment,
            image == NULL ? "" : image);
        if (err < 0) {
            return err;
        }
        if (end <= address) {
            break;
        }
        address = end;
    }
    return 0;
}

static int mira_hostfs_append_task(mira_hostfs_buffer_t *buffer) {
    task_vm_info_data_t vm_info = {};
    mach_msg_type_number_t vm_count = TASK_VM_INFO_COUNT;
    kern_return_t kr = task_info(mach_task_self(), TASK_VM_INFO, (task_info_t) &vm_info, &vm_count);
    if (kr == KERN_SUCCESS) {
        int err = mira_hostfs_buffer_appendf(buffer,
            "virtual_size=%llu\n"
            "resident_size=%llu\n"
            "resident_size_peak=%llu\n"
            "phys_footprint=%llu\n"
            "internal=%llu\n"
            "compressed=%llu\n"
            "external=%llu\n"
            "reusable=%llu\n",
            (unsigned long long) vm_info.virtual_size,
            (unsigned long long) vm_info.resident_size,
            (unsigned long long) vm_info.resident_size_peak,
            (unsigned long long) vm_info.phys_footprint,
            (unsigned long long) vm_info.internal,
            (unsigned long long) vm_info.compressed,
            (unsigned long long) vm_info.external,
            (unsigned long long) vm_info.reusable);
        if (err < 0) {
            return err;
        }
    } else {
        int err = mira_hostfs_buffer_appendf(buffer, "task_vm_info_error=%s\n", mach_error_string(kr));
        if (err < 0) {
            return err;
        }
    }

    task_basic_info_64_data_t basic = {};
    mach_msg_type_number_t basic_count = TASK_BASIC_INFO_64_COUNT;
    kr = task_info(mach_task_self(), TASK_BASIC_INFO_64, (task_info_t) &basic, &basic_count);
    if (kr == KERN_SUCCESS) {
        return mira_hostfs_buffer_appendf(buffer,
            "suspend_count=%d\n"
            "user_time=%d.%06d\n"
            "system_time=%d.%06d\n",
            basic.suspend_count,
            basic.user_time.seconds,
            basic.user_time.microseconds,
            basic.system_time.seconds,
            basic.system_time.microseconds);
    }
    return mira_hostfs_buffer_appendf(buffer, "task_basic_info_error=%s\n", mach_error_string(kr));
}

static int mira_hostfs_generate_file(mira_hostfs_node_t node, char **data_out, size_t *size_out) {
    mira_hostfs_buffer_t buffer = {};
    int err = 0;
    switch (node) {
        case MIRA_HOSTFS_NODE_SUMMARY:
            err = mira_hostfs_append_summary(&buffer);
            break;
        case MIRA_HOSTFS_NODE_PATHS:
            err = mira_hostfs_append_paths(&buffer);
            break;
        case MIRA_HOSTFS_NODE_BUNDLE:
            err = mira_hostfs_append_bundle(&buffer);
            break;
        case MIRA_HOSTFS_NODE_IMAGES:
            err = mira_hostfs_append_images(&buffer);
            break;
        case MIRA_HOSTFS_NODE_MAPS:
            err = mira_hostfs_append_maps(&buffer);
            break;
        case MIRA_HOSTFS_NODE_TASK:
            err = mira_hostfs_append_task(&buffer);
            break;
        case MIRA_HOSTFS_NODE_ROOT:
            err = _EISDIR;
            break;
    }
    if (err < 0) {
        free(buffer.data);
        return err;
    }
    if (buffer.data == NULL) {
        buffer.data = strdup("");
        if (buffer.data == NULL) {
            return _ENOMEM;
        }
    }
    *data_out = buffer.data;
    *size_out = buffer.length;
    return 0;
}

static void mira_hostfs_fill_stat(mira_hostfs_node_t node, struct statbuf *stat, size_t size) {
    memset(stat, 0, sizeof(*stat));
    stat->dev = 0x6d697261;
    stat->inode = mira_hostfs_inode_for_node(node);
    stat->mode = node == MIRA_HOSTFS_NODE_ROOT ? (S_IFDIR | 0555) : (S_IFREG | 0444);
    stat->nlink = node == MIRA_HOSTFS_NODE_ROOT ? 2 : 1;
    stat->uid = 0;
    stat->gid = 0;
    stat->size = node == MIRA_HOSTFS_NODE_ROOT ? 0 : size;
    stat->blksize = 4096;
    stat->blocks = (stat->size + 511U) / 512U;
    struct timeval now;
    gettimeofday(&now, NULL);
    stat->atime = (dword_t) now.tv_sec;
    stat->mtime = (dword_t) now.tv_sec;
    stat->ctime = (dword_t) now.tv_sec;
    stat->atime_nsec = (dword_t) now.tv_usec * 1000U;
    stat->mtime_nsec = stat->atime_nsec;
    stat->ctime_nsec = stat->atime_nsec;
}

static struct fd *mira_hostfs_open(struct mount *UNUSED(mount), const char *path, int flags, int UNUSED(mode)) {
    if (flags & (O_WRONLY_ | O_RDWR_ | O_CREAT_ | O_TRUNC_)) {
        return ERR_PTR(_EROFS);
    }

    mira_hostfs_node_t node = MIRA_HOSTFS_NODE_ROOT;
    if (!mira_hostfs_lookup_node(path, &node)) {
        return ERR_PTR(_ENOENT);
    }

    mira_hostfs_fd_t *host_fd = (mira_hostfs_fd_t *) calloc(1, sizeof(*host_fd));
    if (host_fd == NULL) {
        return ERR_PTR(_ENOMEM);
    }
    host_fd->node = node;
    if (node != MIRA_HOSTFS_NODE_ROOT) {
        int err = mira_hostfs_generate_file(node, &host_fd->data, &host_fd->size);
        if (err < 0) {
            free(host_fd);
            return ERR_PTR(err);
        }
    }

    extern const struct fd_ops mira_hostfs_fdops;
    struct fd *fd = fd_create(&mira_hostfs_fdops);
    if (fd == NULL) {
        free(host_fd->data);
        free(host_fd);
        return ERR_PTR(_ENOMEM);
    }
    fd->fs_data = host_fd;
    return fd;
}

static int mira_hostfs_stat(struct mount *UNUSED(mount), const char *path, struct statbuf *stat) {
    mira_hostfs_node_t node = MIRA_HOSTFS_NODE_ROOT;
    if (!mira_hostfs_lookup_node(path, &node)) {
        return _ENOENT;
    }
    size_t size = 0;
    if (node != MIRA_HOSTFS_NODE_ROOT) {
        char *data = NULL;
        int err = mira_hostfs_generate_file(node, &data, &size);
        free(data);
        if (err < 0) {
            return err;
        }
    }
    mira_hostfs_fill_stat(node, stat, size);
    return 0;
}

static int mira_hostfs_fstat(struct fd *fd, struct statbuf *stat) {
    mira_hostfs_fd_t *host_fd = (mira_hostfs_fd_t *) fd->fs_data;
    if (host_fd == NULL) {
        return _EBADF;
    }
    mira_hostfs_fill_stat(host_fd->node, stat, host_fd->size);
    return 0;
}

static int mira_hostfs_getpath(struct fd *fd, char *buf) {
    mira_hostfs_fd_t *host_fd = (mira_hostfs_fd_t *) fd->fs_data;
    if (host_fd == NULL) {
        return _EBADF;
    }
    const char *name = mira_hostfs_node_name(host_fd->node);
    if (host_fd->node == MIRA_HOSTFS_NODE_ROOT) {
        buf[0] = '\0';
    } else {
        snprintf(buf, MAX_PATH, "/%s", name);
    }
    return 0;
}

static ssize_t mira_hostfs_pread(struct fd *fd, void *buf, size_t bufsize, off_t off) {
    mira_hostfs_fd_t *host_fd = (mira_hostfs_fd_t *) fd->fs_data;
    if (host_fd == NULL) {
        return _EBADF;
    }
    if (host_fd->node == MIRA_HOSTFS_NODE_ROOT) {
        return _EISDIR;
    }
    if (off < 0) {
        return _EINVAL;
    }
    size_t offset = (size_t) off;
    if (offset >= host_fd->size) {
        return 0;
    }
    size_t remaining = host_fd->size - offset;
    size_t count = remaining < bufsize ? remaining : bufsize;
    memcpy(buf, host_fd->data + offset, count);
    return (ssize_t) count;
}

static off_t_ mira_hostfs_lseek(struct fd *fd, off_t_ off, int whence) {
    mira_hostfs_fd_t *host_fd = (mira_hostfs_fd_t *) fd->fs_data;
    if (host_fd == NULL) {
        return _EBADF;
    }
    size_t size = host_fd->node == MIRA_HOSTFS_NODE_ROOT
        ? sizeof(g_mira_hostfs_entries) / sizeof(g_mira_hostfs_entries[0]) + 2U
        : host_fd->size;
    return generic_seek(fd, off, whence, size);
}

static int mira_hostfs_readdir(struct fd *fd, struct dir_entry *entry) {
    mira_hostfs_fd_t *host_fd = (mira_hostfs_fd_t *) fd->fs_data;
    if (host_fd == NULL) {
        return _EBADF;
    }
    if (host_fd->node != MIRA_HOSTFS_NODE_ROOT) {
        return _ENOTDIR;
    }

    unsigned long index = fd->offset++;
    if (index == 0) {
        entry->inode = mira_hostfs_inode_for_node(MIRA_HOSTFS_NODE_ROOT);
        strcpy(entry->name, ".");
        return 1;
    }
    if (index == 1) {
        entry->inode = mira_hostfs_inode_for_node(MIRA_HOSTFS_NODE_ROOT);
        strcpy(entry->name, "..");
        return 1;
    }
    index -= 2;
    if (index >= sizeof(g_mira_hostfs_entries) / sizeof(g_mira_hostfs_entries[0])) {
        return 0;
    }
    entry->inode = mira_hostfs_inode_for_node(g_mira_hostfs_entries[index].node);
    snprintf(entry->name, sizeof(entry->name), "%s", g_mira_hostfs_entries[index].name);
    return 1;
}

static int mira_hostfs_close(struct fd *fd) {
    mira_hostfs_fd_t *host_fd = (mira_hostfs_fd_t *) fd->fs_data;
    if (host_fd != NULL) {
        free(host_fd->data);
        free(host_fd);
        fd->fs_data = NULL;
    }
    return 0;
}

const struct fd_ops mira_hostfs_fdops = {
    .pread = mira_hostfs_pread,
    .lseek = mira_hostfs_lseek,
    .readdir = mira_hostfs_readdir,
    .close = mira_hostfs_close,
};

static const struct fs_ops mira_hostfs = {
    .name = "mira-host",
    .magic = 0x6d697261,
    .open = mira_hostfs_open,
    .stat = mira_hostfs_stat,
    .fstat = mira_hostfs_fstat,
    .getpath = mira_hostfs_getpath,
};

int mira_ish_hostfs_mount(void) {
    (void) generic_mkdirat(AT_PWD, "/mira", 0755);
    (void) generic_mkdirat(AT_PWD, "/mira/host", 0755);
    int err = do_mount(&mira_hostfs, "mira-host", "/mira/host", "", MS_NOSUID_ | MS_NODEV_ | MS_NOEXEC_);
    return err;
}

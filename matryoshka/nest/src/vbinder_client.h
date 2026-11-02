// Client side of vbinder, linked into libnest_preload.so. Makes /dev/binder, /dev/hwbinder and
// /dev/vndbinder behave like the kernel driver for the guest's unmodified libbinder.
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstddef>
#include <sys/types.h>

namespace nest::vbinder {

// Returns a binder fd for a binder device path, -1 with errno on failure, or -2 when `path` is
// not a binder device (or vbinder is not configured) and the caller should proceed normally.
int clientOpen(const char* path, int flags);
bool clientIsBinderFd(int fd);
int clientIoctl(int fd, unsigned long request, void* arg);
void* clientMmap(void* addr, size_t len, int prot, int flags, int fd, off_t off);
void clientClose(int fd);

}  // namespace nest::vbinder

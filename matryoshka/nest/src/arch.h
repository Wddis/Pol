// Register access for syscall interception (arm64 = phones, x86_64 = host tests), including
// 32-bit (compat) processes: arm32 on arm64, i386 on x86_64.
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstdint>
#include <sys/types.h>

namespace nest {

struct Regs {
    uint64_t nr = 0;        // syscall number
    uint64_t arg[6] = {};   // arguments
    uint64_t ret = 0;       // return value (valid at syscall exit)
    uint64_t sp = 0;        // stack pointer
    uint64_t pc = 0;
    bool compat = false;    // 32-bit process (arm32 on arm64, i386 on x86_64)
#if defined(__x86_64__)
    unsigned char raw[27 * 8];  // struct user_regs_struct
#elif defined(__aarch64__)
    unsigned char raw[34 * 8];  // struct user_pt_regs
#endif
};

// Reads registers of a stopped tracee. At a seccomp stop, arg[] are the syscall arguments.
bool getRegs(pid_t tid, Regs& r);
// Writes back arg[] (and sp) changes. On arm64 the syscall number is written separately.
bool setArgs(pid_t tid, Regs& r);
// Changes the syscall number; -1 skips the syscall (then setReturn decides its result).
bool setSyscall(pid_t tid, Regs& r, long nr);
// Sets the return value (at syscall exit, or together with a skipped syscall).
bool setReturn(pid_t tid, Regs& r, long value);

// Copies memory to/from the tracee.
bool readMem(pid_t tid, uint64_t addr, void* buf, size_t len);
bool writeMem(pid_t tid, uint64_t addr, const void* buf, size_t len);
// Reads a NUL-terminated string (max len bytes).
bool readString(pid_t tid, uint64_t addr, char* buf, size_t max);

}  // namespace nest

// Fast path shared by the tracer's seccomp filter and the in-process preload library.
//
// A syscall issued with fastPathTag(nr) in the 6th argument register (x5 on arm64, r9 on x86_64)
// has already been translated inside the guest process, so the seccomp filter lets it through
// without stopping the process. None of the intercepted syscalls uses a 6th argument, so the tag
// is inert for the kernel. execve is never allowed this way: it always needs the tracer.
//
// The tag is bound to the syscall number and the register is cleared right after the call: the
// kernel preserves registers, and a tag left behind would otherwise let the *next* raw libc
// syscall (e.g. fchown after open) bypass the tracer.
//
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <sys/stat.h>

#include <cstdint>
#include <sys/syscall.h>

namespace nest {

constexpr uint64_t kFastPathTag = 0x4e4553546d766d21ull;  // "NESTmvm!" (32-bit: low word only)

// Virtual owner of a guest file, "uid:gid" (written by the tracer on chown, read by the preload).
constexpr const char* kOwnerXattr = "user.nest.owner";

// The tag a call with syscall number `nr` must carry (the filter computes the same XOR).
constexpr uint64_t fastPathTag(long nr) { return kFastPathTag ^ static_cast<uint32_t>(nr); }

// Carrier for a bpf() call that the kernel or a CPU emulator rejects before the tracer sees it
// (qemu-user on the development bench has no bpf()): mount(NULL, "/", kNestBpfFsType,
// cmd | size << kNestBpfSizeShift, attr). The tracer answers it exactly like bpf(); qemu passes
// mount()'s data pointer through untouched.
constexpr const char* kNestBpfFsType = "nest-bpf";
constexpr int kNestBpfSizeShift = sizeof(long) == 8 ? 32 : 16;

// fstatat with the kernel's 64-bit stat layout on every ABI.
#if defined(SYS_newfstatat)
constexpr long kSysFstatat = SYS_newfstatat;
#else
constexpr long kSysFstatat = SYS_fstatat64;
#endif

inline long taggedSyscall(long nr, long a0 = 0, long a1 = 0, long a2 = 0, long a3 = 0, long a4 = 0) {
#if defined(__aarch64__)
    register long x8 asm("x8") = nr;
    register long x0 asm("x0") = a0;
    register long x1 asm("x1") = a1;
    register long x2 asm("x2") = a2;
    register long x3 asm("x3") = a3;
    register long x4 asm("x4") = a4;
    register long x5 asm("x5") = static_cast<long>(fastPathTag(nr));
    asm volatile("svc #0\n\tmov x5, xzr" : "+r"(x0), "+r"(x5) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4)
                 : "memory", "cc");
    return x0;
#elif defined(__x86_64__)
    long ret;
    register long r10 asm("r10") = a3;
    register long r8 asm("r8") = a4;
    register long r9 asm("r9") = static_cast<long>(fastPathTag(nr));
    asm volatile("syscall\n\txor %%r9d, %%r9d"
                 : "=a"(ret), "+r"(r9)
                 : "a"(nr), "D"(a0), "S"(a1), "d"(a2), "r"(r10), "r"(r8)
                 : "rcx", "r11", "memory");
    return ret;
#elif defined(__i386__)
    // 32-bit: only the low word of the tag fits; ebp is the 6th argument register (restored).
    long ret;
    long tag = static_cast<long>(fastPathTag(nr) & 0xffffffffu);
    // The tag is pushed first: %7 may be esp-relative, so it is read before esp moves.
    asm volatile("push %7\n\tpush %%ebp\n\tmov 4(%%esp), %%ebp\n\tint $0x80\n\tpop %%ebp\n\tadd $4, %%esp"
                 : "=a"(ret)
                 : "a"(nr), "b"(a0), "c"(a1), "d"(a2), "S"(a3), "D"(a4), "m"(tag)
                 : "memory");
    return ret;
#elif defined(__arm__)
    register long r7 asm("r7") = nr;
    register long r0 asm("r0") = a0;
    register long r1 asm("r1") = a1;
    register long r2 asm("r2") = a2;
    register long r3 asm("r3") = a3;
    register long r4 asm("r4") = a4;
    register long r5 asm("r5") = static_cast<long>(fastPathTag(nr) & 0xffffffffu);
    asm volatile("svc #0\n\tmov r5, #0" : "+r"(r0), "+r"(r5) : "r"(r7), "r"(r1), "r"(r2), "r"(r3), "r"(r4)
                 : "memory", "cc");
    return r0;
#else
#error unsupported architecture
#endif
}

// Inode of an open fd, via the fast path (no tracer stop). 0 if the fd is closed.
inline unsigned long fdInodeFast(int fd) {
#if defined(__LP64__)
    struct stat st;
    if (taggedSyscall(SYS_fstat, fd, reinterpret_cast<long>(&st)) != 0) return 0;
#else
    struct stat64 st;
    if (taggedSyscall(SYS_fstat64, fd, reinterpret_cast<long>(&st)) != 0) return 0;
#endif
    return static_cast<unsigned long>(st.st_ino);
}

// Plain six-argument syscall (no tag), for the preload's syscall() wrapper.
#if defined(__aarch64__) || defined(__x86_64__) || defined(__arm__)
inline long rawSyscall(long nr, long a0, long a1, long a2, long a3, long a4, long a5) {
#if defined(__aarch64__)
    register long x8 asm("x8") = nr;
    register long x0 asm("x0") = a0;
    register long x1 asm("x1") = a1;
    register long x2 asm("x2") = a2;
    register long x3 asm("x3") = a3;
    register long x4 asm("x4") = a4;
    register long x5 asm("x5") = a5;
    asm volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory", "cc");
    return x0;
#elif defined(__x86_64__)
    long ret;
    register long r10 asm("r10") = a3;
    register long r8 asm("r8") = a4;
    register long r9 asm("r9") = a5;
    asm volatile("syscall"
                 : "=a"(ret)
                 : "a"(nr), "D"(a0), "S"(a1), "d"(a2), "r"(r10), "r"(r8), "r"(r9)
                 : "rcx", "r11", "memory");
    return ret;
#else
    register long r7 asm("r7") = nr;
    register long r0 asm("r0") = a0;
    register long r1 asm("r1") = a1;
    register long r2 asm("r2") = a2;
    register long r3 asm("r3") = a3;
    register long r4 asm("r4") = a4;
    register long r5 asm("r5") = a5;
    asm volatile("svc #0" : "+r"(r0) : "r"(r7), "r"(r1), "r"(r2), "r"(r3), "r"(r4), "r"(r5) : "memory", "cc");
    return r0;
#endif
}
#define NEST_HAVE_RAW_SYSCALL 1
#endif

}  // namespace nest

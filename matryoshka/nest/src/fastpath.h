// Fast path shared by the tracer's seccomp filter and the in-process preload library.
//
// A syscall issued with kFastPathTag in the 6th argument register (x5 on arm64, r9 on x86_64) has
// already been translated inside the guest process, so the seccomp filter lets it through without
// stopping the process. None of the intercepted syscalls uses a 6th argument, so the tag is inert
// for the kernel. execve is never allowed this way: it always needs the tracer.
//
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstdint>

namespace nest {

constexpr uint64_t kFastPathTag = 0x4e4553546d766d21ull;  // "NESTmvm!"

inline long taggedSyscall(long nr, long a0 = 0, long a1 = 0, long a2 = 0, long a3 = 0, long a4 = 0) {
#if defined(__aarch64__)
    register long x8 asm("x8") = nr;
    register long x0 asm("x0") = a0;
    register long x1 asm("x1") = a1;
    register long x2 asm("x2") = a2;
    register long x3 asm("x3") = a3;
    register long x4 asm("x4") = a4;
    register long x5 asm("x5") = static_cast<long>(kFastPathTag);
    asm volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory", "cc");
    return x0;
#elif defined(__x86_64__)
    long ret;
    register long r10 asm("r10") = a3;
    register long r8 asm("r8") = a4;
    register long r9 asm("r9") = static_cast<long>(kFastPathTag);
    asm volatile("syscall"
                 : "=a"(ret)
                 : "a"(nr), "D"(a0), "S"(a1), "d"(a2), "r"(r10), "r"(r8), "r"(r9)
                 : "rcx", "r11", "memory");
    return ret;
#else
#error unsupported architecture
#endif
}

}  // namespace nest

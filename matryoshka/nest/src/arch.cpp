// SPDX-License-Identifier: Apache-2.0
#include "arch.h"

#include <cerrno>
#include <cstring>
#include <elf.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/user.h>

#ifndef NT_ARM_SYSTEM_CALL
#define NT_ARM_SYSTEM_CALL 0x404
#endif

namespace nest {

#if defined(__x86_64__)

static user_regs_struct* U(Regs& r) { return reinterpret_cast<user_regs_struct*>(r.raw); }

bool getRegs(pid_t tid, Regs& r) {
    if (ptrace(PTRACE_GETREGS, tid, nullptr, r.raw) != 0) return false;
    auto* u = U(r);
    r.compat = u->cs == 0x23;  // i386 code segment
    r.nr = u->orig_rax;
    if (r.compat) {
        r.arg[0] = u->rbx & 0xffffffff; r.arg[1] = u->rcx & 0xffffffff; r.arg[2] = u->rdx & 0xffffffff;
        r.arg[3] = u->rsi & 0xffffffff; r.arg[4] = u->rdi & 0xffffffff; r.arg[5] = u->rbp & 0xffffffff;
    } else {
        r.arg[0] = u->rdi; r.arg[1] = u->rsi; r.arg[2] = u->rdx;
        r.arg[3] = u->r10; r.arg[4] = u->r8; r.arg[5] = u->r9;
    }
    r.ret = u->rax;
    r.sp = r.compat ? (u->rsp & 0xffffffff) : u->rsp;
    r.pc = u->rip;
    return true;
}

bool setArgs(pid_t tid, Regs& r) {
    auto* u = U(r);
    if (r.compat) {
        u->rbx = r.arg[0]; u->rcx = r.arg[1]; u->rdx = r.arg[2];
        u->rsi = r.arg[3]; u->rdi = r.arg[4]; u->rbp = r.arg[5];
    } else {
        u->rdi = r.arg[0]; u->rsi = r.arg[1]; u->rdx = r.arg[2];
        u->r10 = r.arg[3]; u->r8 = r.arg[4]; u->r9 = r.arg[5];
    }
    return ptrace(PTRACE_SETREGS, tid, nullptr, r.raw) == 0;
}

bool setSyscall(pid_t tid, Regs& r, long nr) {
    U(r)->orig_rax = static_cast<uint64_t>(nr);
    r.nr = static_cast<uint64_t>(nr);
    return ptrace(PTRACE_SETREGS, tid, nullptr, r.raw) == 0;
}

bool restartAs(pid_t tid, Regs& r, long nr) {
    auto* u = U(r);
    if (r.compat) {
        u->rbx = r.arg[0]; u->rcx = r.arg[1]; u->rdx = r.arg[2];
        u->rsi = r.arg[3]; u->rdi = r.arg[4]; u->rbp = r.arg[5];
    } else {
        u->rdi = r.arg[0]; u->rsi = r.arg[1]; u->rdx = r.arg[2];
        u->r10 = r.arg[3]; u->r8 = r.arg[4]; u->r9 = r.arg[5];
    }
    u->rax = static_cast<uint64_t>(nr);
    u->rip -= 2;  // syscall / int $0x80
    return ptrace(PTRACE_SETREGS, tid, nullptr, r.raw) == 0;
}

bool setReturn(pid_t tid, Regs& r, long value) {
    U(r)->rax = r.compat ? static_cast<uint64_t>(static_cast<uint32_t>(value)) : static_cast<uint64_t>(value);
    r.ret = static_cast<uint64_t>(value);
    return ptrace(PTRACE_SETREGS, tid, nullptr, r.raw) == 0;
}

#elif defined(__aarch64__)

struct ArmRegs { uint64_t regs[31]; uint64_t sp; uint64_t pc; uint64_t pstate; };
static ArmRegs* A(Regs& r) { return reinterpret_cast<ArmRegs*>(r.raw); }
// arm32 (compat) NT_PRSTATUS: r0..r15, cpsr, orig_r0 as 32-bit words.
static uint32_t* A32(Regs& r) { return reinterpret_cast<uint32_t*>(r.raw); }
constexpr size_t kArm32RegsSize = 18 * 4;

bool getRegs(pid_t tid, Regs& r) {
    iovec io{r.raw, sizeof(ArmRegs)};
    if (ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(NT_PRSTATUS), &io) != 0) return false;
    r.compat = io.iov_len == kArm32RegsSize;
    if (r.compat) {
        uint32_t* g = A32(r);
        for (int i = 0; i < 6; i++) r.arg[i] = g[i];
        r.ret = g[0];
        r.sp = g[13];
        r.pc = g[15];
        int nr = 0;
        iovec sio{&nr, sizeof(nr)};
        r.nr = ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(NT_ARM_SYSTEM_CALL), &sio) == 0 ? nr : g[7];
        return true;
    }
    auto* a = A(r);
    for (int i = 0; i < 6; i++) r.arg[i] = a->regs[i];
    r.ret = a->regs[0];
    r.sp = a->sp;
    r.pc = a->pc;
    int nr = 0;
    iovec sio{&nr, sizeof(nr)};
    if (ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(NT_ARM_SYSTEM_CALL), &sio) == 0) r.nr = nr;
    else r.nr = a->regs[8];
    return true;
}

static bool putRegs(pid_t tid, Regs& r) {
    iovec io{r.raw, r.compat ? kArm32RegsSize : sizeof(ArmRegs)};
    return ptrace(PTRACE_SETREGSET, tid, reinterpret_cast<void*>(NT_PRSTATUS), &io) == 0;
}

bool setArgs(pid_t tid, Regs& r) {
    if (r.compat) {
        uint32_t* g = A32(r);
        for (int i = 0; i < 6; i++) g[i] = static_cast<uint32_t>(r.arg[i]);
        g[13] = static_cast<uint32_t>(r.sp);
        return putRegs(tid, r);
    }
    auto* a = A(r);
    for (int i = 0; i < 6; i++) a->regs[i] = r.arg[i];
    a->sp = r.sp;
    return putRegs(tid, r);
}

bool setSyscall(pid_t tid, Regs& r, long nr) {
    int n = static_cast<int>(nr);
    iovec sio{&n, sizeof(n)};
    r.nr = static_cast<uint64_t>(nr);
    return ptrace(PTRACE_SETREGSET, tid, reinterpret_cast<void*>(NT_ARM_SYSTEM_CALL), &sio) == 0;
}

bool restartAs(pid_t tid, Regs& r, long nr) {
    if (r.compat) {
        uint32_t* g = A32(r);
        for (int i = 0; i < 6; i++) g[i] = static_cast<uint32_t>(r.arg[i]);
        g[7] = static_cast<uint32_t>(nr);
        g[15] -= (g[16] & 0x20) ? 2 : 4;  // Thumb or ARM svc
        return putRegs(tid, r);
    }
    auto* a = A(r);
    for (int i = 0; i < 6; i++) a->regs[i] = r.arg[i];
    a->regs[8] = static_cast<uint64_t>(nr);
    a->pc -= 4;
    return putRegs(tid, r);
}

bool setReturn(pid_t tid, Regs& r, long value) {
    if (r.compat) A32(r)[0] = static_cast<uint32_t>(value);
    else A(r)->regs[0] = static_cast<uint64_t>(value);
    r.ret = static_cast<uint64_t>(value);
    return putRegs(tid, r);
}

#else
#error "Nest supports arm64 and x86_64 only"
#endif

bool readMem(pid_t tid, uint64_t addr, void* buf, size_t len) {
    addr = untagAddr(addr);
    iovec local{buf, len};
    iovec remote{reinterpret_cast<void*>(addr), len};
    return process_vm_readv(tid, &local, 1, &remote, 1, 0) == static_cast<ssize_t>(len);
}

bool writeMem(pid_t tid, uint64_t addr, const void* buf, size_t len) {
    addr = untagAddr(addr);
    iovec local{const_cast<void*>(buf), len};
    iovec remote{reinterpret_cast<void*>(addr), len};
    if (process_vm_writev(tid, &local, 1, &remote, 1, 0) == static_cast<ssize_t>(len)) return true;
    // Fallback for pages process_vm_writev refuses (e.g. read-only mappings): word-wise poke.
    const auto* src = static_cast<const unsigned char*>(buf);
    for (size_t off = 0; off < len; off += sizeof(long)) {
        long word = 0;
        size_t n = len - off < sizeof(long) ? len - off : sizeof(long);
        if (n < sizeof(long)) {
            errno = 0;
            word = ptrace(PTRACE_PEEKDATA, tid, reinterpret_cast<void*>(addr + off), nullptr);
            if (errno) return false;
        }
        memcpy(&word, src + off, n);
        if (ptrace(PTRACE_POKEDATA, tid, reinterpret_cast<void*>(addr + off), reinterpret_cast<void*>(word)) != 0)
            return false;
    }
    return true;
}

bool readString(pid_t tid, uint64_t addr, char* buf, size_t max) {
    addr = untagAddr(addr);
    // Read in chunks that never cross a page boundary (the next page may be unmapped).
    size_t got = 0;
    while (got < max) {
        uint64_t a = addr + got;
        size_t pageLeft = 4096 - (a & 4095);
        size_t chunk = pageLeft < max - got ? pageLeft : max - got;
        if (!readMem(tid, a, buf + got, chunk)) return false;
        if (memchr(buf + got, 0, chunk)) return true;
        got += chunk;
    }
    buf[max - 1] = 0;
    return false;  // too long
}

}  // namespace nest

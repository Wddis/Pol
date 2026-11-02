// Minimal test framework (no external dependencies, fast to build on any host).
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstdio>
#include <functional>
#include <string>
#include <vector>

namespace t {

struct Case {
    const char* name;
    std::function<void()> fn;
};

inline std::vector<Case>& registry() {
    static std::vector<Case> r;
    return r;
}
inline int& failures() {
    static int f = 0;
    return f;
}

struct Reg {
    Reg(const char* n, std::function<void()> f) { registry().push_back({n, std::move(f)}); }
};

}  // namespace t

#define TEST(name)                                     \
    static void test_##name();                         \
    static t::Reg reg_##name(#name, test_##name);      \
    static void test_##name()

#define EXPECT_TRUE(c)                                                               \
    do {                                                                             \
        if (!(c)) {                                                                  \
            fprintf(stderr, "  FAIL %s:%d: %s\n", __FILE__, __LINE__, #c);           \
            t::failures()++;                                                         \
        }                                                                            \
    } while (0)

#define EXPECT_EQ(a, b)                                                                          \
    do {                                                                                         \
        auto va = (a);                                                                           \
        auto vb = (b);                                                                           \
        if (!(va == vb)) {                                                                       \
            fprintf(stderr, "  FAIL %s:%d: %s == %s\n    got: [%s]\n    exp: [%s]\n", __FILE__, \
                    __LINE__, #a, #b, t::str(va).c_str(), t::str(vb).c_str());                   \
            t::failures()++;                                                                     \
        }                                                                                        \
    } while (0)

namespace t {
inline std::string str(const std::string& s) { return s; }
inline std::string str(const char* s) { return s ? s : "(null)"; }
inline std::string str(bool b) { return b ? "true" : "false"; }
template <typename T>
std::string str(T v) { return std::to_string(v); }
}  // namespace t

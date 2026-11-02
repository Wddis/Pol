// SPDX-License-Identifier: Apache-2.0
#include <cstring>

#include "test.h"

int main(int argc, char** argv) {
    const char* filter = argc > 1 ? argv[1] : nullptr;
    int run = 0;
    for (auto& c : t::registry()) {
        if (filter && !strstr(c.name, filter)) continue;
        int before = t::failures();
        fprintf(stderr, "[ RUN  ] %s\n", c.name);
        c.fn();
        fprintf(stderr, "[ %s ] %s\n", t::failures() == before ? " OK " : "FAIL", c.name);
        run++;
    }
    fprintf(stderr, "\n%d tests, %d failures\n", run, t::failures());
    return t::failures() == 0 ? 0 : 1;
}

// Capability probe: verifies on the real device every kernel/OS mechanism Nest relies on.
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <string>

namespace nest {

// Runs all checks using `workDir` (a writable directory) and returns a report of lines:
//   info.<key>=<value>
//   check.<name>=ok[:detail] | fail:<detail>
std::string runProbe(const std::string& workDir);

}  // namespace nest

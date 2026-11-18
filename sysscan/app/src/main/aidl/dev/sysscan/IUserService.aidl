package dev.sysscan;

interface IUserService {
    // 16777114 зарезервирован Shizuku под destroy (как в официальном demo)
    void destroy() = 16777114;
    String exec(String cmd) = 1;
}

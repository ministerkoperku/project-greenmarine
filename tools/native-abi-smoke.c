/* Run against the real shared library in its matching libc process. No GPU required.
 * Android example: aarch64-linux-android-clang tools/native-abi-smoke.c -ldl -lm -o smoke
 * Execute the binary from app-private executable storage, passing the .so path.
 */
#include <dlfcn.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>

int main(int argc, char **argv) {
    if (argc != 2) { fprintf(stderr, "Usage: native-abi-smoke /absolute/library.so\n"); return 2; }
    void *library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!library) { fprintf(stderr, "dlopen: %s\n", dlerror()); return 1; }
    int (*abi)(void) = dlsym(library, "greenmarine_abi_version");
    float (*sample)(float, float) = dlsym(library, "greenmarine_sample");
    int (*attach)(uint64_t, uint64_t, uint64_t, uint64_t, uint32_t) = dlsym(library, "greenmarine_attach");
    void (*detach)(void) = dlsym(library, "greenmarine_detach");
    int (*rt_abi)(void) = dlsym(library, "greenmarine_rt_api_version");
    int (*surface_abi)(void) = dlsym(library, "greenmarine_surface_rt_api_version");
    int (*async_abi)(void) = dlsym(library, "greenmarine_rt_async_api_version");
    if (!abi || !sample || !attach || !detach || !rt_abi || !surface_abi || !async_abi) {
        fprintf(stderr, "Missing ABI symbol\n"); dlclose(library); return 1;
    }
    int ok = abi() == 1 && rt_abi() == 1 && surface_abi() == 1 && async_abi() == 1
        && fabsf(sample(0, 2) - 1.5f) < 0.00001f
        && fabsf(sample(1.57079632679f, 2) - 2) < 0.00001f
        && sample(NAN, 2) == 0 && attach(0, 0, 0, 0, 0) == -1;
    detach();
    dlclose(library);
    puts(ok ? "PASS: base, world RT, surface RT and async ABI; sampling; null host rejection" : "FAIL: native ABI contract");
    return ok ? 0 : 1;
}

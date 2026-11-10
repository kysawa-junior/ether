# Building Ether

## Prerequisites

| Requirement | Notes |
|---|---|
| Linux, macOS, or Windows | Linux is the current validated development host |
| Network access on first build | The Amper wrapper downloads its toolchain and Kotlin/Native distributions |
| C toolchain | Kotlin/Native linking needs a host linker (GCC/Clang on Linux, Xcode on macOS, MinGW on Windows) |
| Android SDK | Provisioned by the toolchain or supplied via the standard Android environment variables |
| Xcode + macOS | Required only for iOS targets; not available on Linux |
| Disk space | Kotlin/Native and Gradle-style caches require several GB |

You do **not** need to install JDK, Kotlin, or Gradle manually. `./kotlin` provisions the Kotlin
Toolchain (Amper 0.13.0) and a matching JRE.

## First build

From the repository root:

```bash
./kotlin build
```

The first run downloads the toolchain and native distributions and can take several minutes. Later
runs are incremental.

Expected successful output ends with:

```
Build successful
```

The build compiles all six modules for `jvm`, `android`, `linuxX64`, `mingwX64`, and `wasmJs`. iOS
targets are declared but skipped on Linux because Apple targets require macOS.

## Common commands

| Command | Purpose |
|---|---|
| `./kotlin build` | Compile and link every module for every target |
| `./kotlin build -p jvm` | Build only the JVM target |
| `./kotlin build -p linuxX64` | Build only Linux native |
| `./kotlin build -p wasmJs` | Build only the web target |
| `./kotlin build -v release` | Build the release variant (debug is the default) |
| `./kotlin test` | Run tests for all runnable targets |
| `./kotlin test -p jvm` | Run JVM tests only |
| `./kotlin test -p wasmJs` | Run Wasm tests headlessly |
| `./kotlin test --include-module=ether-math --include-classes=dev.instrum.ether.math.*` | Run a test subset |
| `./kotlin test --include-module=ether-core --include-test=<fqn>` | Run one test method |

`--module=<name>` restricts a build to one module. Run `./kotlin --help` or the toolchain's
`show modules` command to list exact module identifiers.

Amper writes build outputs under `build/tasks/<module>_<task>`. The directory can be deleted to
force a clean build:

```bash
rm -rf build
```

Do not delete `~/.cache/JetBrains/Kotlin` unless you want to re-download the toolchain.

## What gets built

| Module | Targets | Output |
|---|---|---|
| `ether-math` | all declared | Kotlin Multiplatform library |
| `ether-core` | all declared | Kotlin Multiplatform library |
| `ether-testing` | all declared | Kotlin Multiplatform test-support library |
| `ether-render-api` | all declared | Kotlin Multiplatform library (portable render types and HAL) |
| `ether-render-core` | all declared | Kotlin Multiplatform library (device facade, validation, linear graph) |
| `ether-render-null` | all declared | Kotlin Multiplatform library (headless backend) |
| `ether-render-vulkan` | linuxX64 (+ Android source set not started) | Native-only backend; dlopen's `libvulkan.so.1` at runtime, never links Vulkan |

The modules have no application entry point yet. There is nothing to run: the rendering modules are
libraries that validate and record commands without producing visual output. The Linux x64 Vulkan
backend rasterizes offscreen for its smoke tests; a headless sample and a windowed sample arrive
with the platform layer.

## Platform notes

### Linux

The validated host. Both `build` and `test` pass for JVM and `linuxX64`. A C toolchain and the
usual native libraries are required for Kotlin/Native linking.

### Windows (`mingwX64`)

Compiles on the Linux host using the MinGW-targeted Kotlin/Native toolchain. It is not runtime-tested;
do not describe Windows as supported until a Windows CI job runs the tests.

### Android

Compiles with the Android SDK. Runtime validation requires an emulator or device. The current code
has no Android-specific dependency beyond the console sink.

### Web (`wasmJs`)

Compiles with Kotlin/Wasm. Tests run headlessly through the Amper test runner, which generates a
Playwright project under `build/tasks/_<module>_testWasmJsDebug/playwright-project`. No browser
rendering test exists because no WebGL2 backend is implemented; the Wasm suite exercises the
portable API, the rendering core, and the null backend.

### iOS

`iosArm64` and `iosSimulatorArm64` are declared in every module. They cannot be built on Linux.
Run the build on macOS with Xcode installed; until that happens in CI, iOS is unvalidated.

## IDE setup

The repository contains an `.idea` directory generated for Amper. Open the project root in IntelliJ
IDEA with Amper support (or Android Studio with the Amper plugin). The project model comes from
`project.yaml` and the module `module.yaml` files; do not create Gradle files.

If the IDE does not recognize the project:

1. confirm the Amper plugin is installed and enabled;
2. re-open the repository root, not a module directory;
3. run `./kotlin build` once from the terminal to materialize the project model.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| First build is very slow | Toolchain and Kotlin/Native download | Wait; subsequent builds are incremental |
| `Unresolved reference` after adding a file | Source set mismatch | Put common code in `src/`, platform code in `src@<target>/` |
| Wasm `js(...)` error | `js()` used outside a single-expression top-level function | Move it into a dedicated function |
| `expect`/`actual` class warning | `expect object`/`expect class` Beta | Prefer `expect fun` or a common implementation |
| iOS build fails on Linux | Apple targets require macOS/Xcode | Build on macOS |
| Android SDK not found | SDK not installed/provisioned | Install the SDK or set `ANDROID_HOME` |
| `Maven coordinates` error in `module.yaml` | Dependency shorthand not resolved | Use a resolvable coordinate or a module path |
| Tests pass on JVM but fail on native | Platform API leak into common code | Move the API behind `expect`/`actual` |

## Verified baseline

The current baseline on the Linux host (2026-11-07):

```
./kotlin build          -> Build successful (6 modules: jvm, android, linuxX64, mingwX64, wasmJs)
./kotlin test           -> JVM summary: 42 math, 41 core, 9 testing, 20 render-api, 77 render-core,
                           8 null backend; 197 successful, 0 failed.
                           linuxX64: 197 test methods started, 0 failed, exit code 0
./kotlin test -p wasmJs -> 197 Passed, 0 Failed, exit code 0
```

iOS targets are declared but cannot be built on this host.

Exact counts and known gaps are recorded in [../development/TESTING.md](../development/TESTING.md).

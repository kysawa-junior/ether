# Multiplatform Strategy

## Target matrix

Ether declares the same target matrix in every module:

| Target | Platform | Toolchain | Build status | Runtime status |
|---|---|---|---|---|
| `jvm` | JVM desktop/server | Kotlin/JVM | Validated | 197/197 tests pass |
| `android` | Android | Kotlin/JVM + Android SDK | Validated (compile) | Not runtime-tested |
| `linuxX64` | Linux x86-64 | Kotlin/Native | Validated | 197 tests, 0 failures |
| `mingwX64` | Windows x86-64 | Kotlin/Native (MinGW) | Validated (compile) | Not runtime-tested |
| `wasmJs` | Browser WebAssembly | Kotlin/Wasm | Validated | 197/197 tests pass (headless) |
| `iosArm64` | iOS device | Kotlin/Native + Xcode | Declared | Untested (requires macOS) |
| `iosSimulatorArm64` | iOS simulator | Kotlin/Native + Xcode | Declared | Untested (requires macOS) |

"Build status: validated" means the module compiles for that target on the current Linux host.
"Runtime status" means tests or a smoke test have actually executed. No target is claimed beyond its
status; see ADR-0017.

### Why iOS is declared but unvalidated

Kotlin/Native Apple targets require Xcode and a macOS host. The current development host is Linux,
so `iosArm64` and `iosSimulatorArm64` are declared to keep the source-set layout honest, but they
cannot be compiled here. They must not be described as supported until macOS CI exists. This is a
hard limitation, not a configuration issue.

## Source set layout

Amper source sets are directory suffixes:

```
modules/<module>/
├── module.yaml
├── src/                  common code (all targets)
├── src@jvm/              JVM-only actuals
├── src@android/          Android-only actuals
├── src@native/           all Kotlin/Native targets (linux, mingw, iOS)
├── src@wasmJs/           Kotlin/Wasm actuals
└── test/                 common tests
```

Current platform-specific code is limited to console logging:

| Source set | File | Implementation |
|---|---|---|
| `src@jvm` | `ConsoleLogSink.jvm.kt` | `println` |
| `src@android` | `ConsoleLogSink.android.kt` | `println` |
| `src@native` | `ConsoleLogSink.native.kt` | `println` |
| `src@wasmJs` | `ConsoleLogSink.wasmJs.kt` | browser `console.log` / `console.error` |

The rendering modules are entirely common code: `ether-render-api`, `ether-render-core`, and
`ether-render-null` contain no platform-specific source sets.

There are no platform-specific time implementations: `kotlin.time.TimeSource.Monotonic` and
`kotlin.time.Clock.System` are multiplatform.

### `expect`/`actual` rules

1. Keep the `expect` surface as small as possible.
2. Prefer a common implementation with a platform hook over parallel platform implementations.
3. Put the `expect` declaration in common code and every `actual` in the matching source set.
4. Document platform behavior differences in the `expect` KDoc.
5. Add a test that exercises the behavior on at least one platform; platform-specific behavior that
   cannot be tested must be marked as such.

Example from Phase 1: `ConsoleLogSink` is a common object; the only platform-specific piece is
`expect fun writeToConsole(record: LogRecord)`. The original design used an `expect object`, which
triggered Kotlin's `expect`/`actual` class Beta warning. Replacing it with an `expect fun` removed
the warning and kept the API stable.

## Kotlin/Wasm constraints learned in Phase 1

These are concrete, verified constraints from building this repository:

1. `js("...")` must be a single expression inside a top-level function body or a property
   initializer. It cannot be embedded in arbitrary expressions.
2. Browser globals such as `console` are not available implicitly. The interop layer needs external
   declarations or a small JS glue module.
3. `println` works on Wasm and is sufficient for the default console sink; the Wasm sink uses
   `js("console.log(text)")` and `js("console.error(text, detail)")` in single-expression functions.
4. Wasm tests run headless through the Amper test runner (Playwright project generated under
   `build/tasks/.../playwright-project`). They are not a browser-rendering test.
5. Shared-memory threads are not assumed. Any future parallel scheduler must degrade to
   single-threaded execution on Wasm.

## Kotlin/Native constraints

- `kotlin.system.getTimeNanos()` is deprecated to an error in Kotlin 2.3+. Use
  `kotlin.time.TimeSource.Monotonic` and `kotlin.time.Clock`.
- Native memory is not managed by the Kotlin GC. GPU resources and native buffers need explicit
  ownership.
- Objective-C interop is available on Apple targets for Metal and platform frameworks; C interop
  (`cinterop`) is the path for Vulkan on Linux/Windows.
- Native targets compile on Linux for Linux and Windows; Apple targets require macOS.
- `HashMap` entry values must be read before `iterator.remove()` is called on that entry. Kotlin/Native
  raises `ConcurrentModificationException` when the value is read after removal, while the JVM
  implementation tolerates it. This bit `GraphicsDevice.pollCompletedSubmissions` on Linux x64.

## JVM and Android

- JVM is the fastest test loop and the reference for common-code behavior.
- Android compiles with the Android SDK provisioned by Amper. Runtime validation requires an
  emulator or device and is not part of the current validation matrix.
- Android logging currently uses `println`; a future platform layer should route to `android.util.Log`
  while keeping the `LogSink` API unchanged.
- The rendering modules are common code, so Android currently inherits the same rendering behavior as
  the JVM and native targets: validation and null-backend recording only.
- JVM desktop packaging (window, input) is a Phase 3 platform-layer concern.

## Publication

Every module publishes as a Kotlin Multiplatform library:

```yaml
settings:
  publishing:
    group: dev.instrum.ether
    artifactId: ether-<module>
    version: 0.1.0
```

Publication requires:

- `explicitApi: strict`;
- all targets configured;
- sources and KDoc for public API;
- no dependencies on test-only modules from production modules.

`ether-testing` is a published module. It depends on `ether-core` and exposes test utilities; it
must not be a dependency of production modules.

Six modules publish today: `ether-math`, `ether-core`, `ether-testing`, `ether-render-api`,
`ether-render-core`, and `ether-render-null`. `ether-render-core` re-exports `ether-render-api` and
`ether-core` so that consumers of the device facade also see handles, capabilities, and engine
exceptions. `ether-render-null` depends only on `ether-render-api`, which keeps it usable without
the core.

## Dependency policy

- Prefer Kotlin standard library and `kotlinx` libraries.
- No JVM-only dependency in common code.
- No dependency that requires a platform API unavailable on Wasm without a capability gate.
- Rendering backends are modules, not dependencies of the core.
- Coroutines (`kotlinx.coroutines`) will be added when async asset loading starts (Phase 3), not
  before. No module depends on coroutines today; the rendering core is synchronous by design.
- Every new dependency needs a documented reason, target support, and license check.

## CI strategy

Current host validation:

```
./kotlin build
./kotlin test
./kotlin test -p wasmJs
```

Measured on the current Linux host: the build succeeds for all six modules across `jvm`, `android`,
`linuxX64`, `mingwX64`, and `wasmJs`; the JVM summary reports 197 successful tests, the native run
starts 197 tests with no failure, and the headless Wasm run reports 197 passes and 0 failures.

Planned CI matrix:

| Job | Host | Commands | Purpose |
|---|---|---|---|
| Linux | ubuntu-latest | `./kotlin build`, `./kotlin test` | JVM + linuxX64 + Wasm |
| Windows | windows-latest | `./kotlin build` | mingwX64 compile |
| macOS | macos-latest | `./kotlin build`, `./kotlin test` | iOS compile + JVM tests |
| Android | ubuntu-latest | `./kotlin build` | Android compile |
| Web smoke | ubuntu-latest | browser test | WebGL2 frame (blocked: backend not started) |

A backend or platform is promoted to "validated" only when its CI job exists and passes.

## File system and resources

- Common code must not assume a working directory or a POSIX file system.
- Asset I/O belongs to the platform/asset layer, never to `ether-core`.
- Web builds fetch assets over HTTP; native/JVM builds use the file system. The asset catalog hides
  the difference behind a resolver.
- Paths in content are relative to the asset root, not absolute.

## Threading and coroutines by target

| Target | Threads | Coroutines | Notes |
|---|---|---|---|
| JVM | Full | Full | Dispatchers available |
| Android | Full | Full | Main dispatcher is the UI thread |
| linuxX64 | Full | Full | Native worker support |
| mingwX64 | Full | Full | Compile-validated only |
| wasmJs | Single-threaded by default | Single-threaded event loop | No shared-memory assumptions |
| iOS | Full | Full | Untested here |

The engine's core and the rendering foundation are single-threaded and do not depend on
coroutines. Future async work must be structured so that it remains correct when all continuations
run on one thread.

## Platform abstraction boundaries

Planned boundaries, each in `ether-platform` or a backend module:

| Boundary | JVM | Android | Native desktop | iOS | Wasm |
|---|---|---|---|---|---|
| Window creation | AWT/Swing or GLFW | SurfaceView/ANativeWindow | GLFW/SDL | UIView/UIWindow | Canvas |
| Input | AWT events | MotionEvent/KeyEvent | GLFW callbacks | UIKit touches | DOM events |
| Display info | GraphicsEnvironment | DisplayMetrics | Monitor APIs | UIScreen | `window` |
| File access | `java.nio` | `Context` assets | POSIX | NSBundle | `fetch` |
| GPU | Vulkan/OpenGL | Vulkan/GLES | Vulkan | Metal | WebGL2/WebGPU |

Only the portable null backend exists today; every GPU cell in the table is a plan for a future
backend module. The table defines where platform code is allowed to live, not what exists.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| iOS untested indefinitely | Declare it, document it, add macOS CI before claiming support |
| Wasm interop friction | Keep a thin, tested JS interop layer; no `js()` in engine logic |
| Native memory bugs | Explicit ownership + null-backend lifetime tests |
| Android runtime differences | Device/emulator smoke tests before support claims |
| Dependency drift | Target support check + license check + explicit approval |
| Amper ecosystem maturity | Keep build logic declarative; document commands; avoid toolchain-specific hacks |

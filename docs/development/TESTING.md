# Testing Ether

## Framework and layout

Tests use `kotlin.test`. Amper maps it to JUnit 5 on JVM, the Kotlin/Native test runner on native
targets, and the Wasm test runner on the web target.

```
modules/<module>/test/<package>/<TestClass>.kt
```

Current suites:

| Module | Suite | Tests | Covers |
|---|---|---:|---|
| `ether-math` | `Vec3Test` | 11 | arithmetic, dot/cross, normalization, lerp, min/max, axis rotation, epsilon |
| `ether-math` | `Mat4Test` | 15 | identity, translation, scale, composition, inverse, determinant, transpose, perspective, lookAt, column-major layout, normal matrix |
| `ether-math` | `QuatTest` | 8 | identity, axis-angle, composition, conjugate, normalization, slerp, Euler angles, invalid axis |
| `ether-math` | `BoundingVolumesTest` | 8 | AABB construction, containment, intersection, union, transform, sphere union/intersection |
| `ether-core` | `EngineLifecycleTest` | 18 | state transitions, hook order, invalid operations, timing, clamping, config validation, failed initialization |
| `ether-core` | `EventBusTest` | 11 | exact-type dispatch, order, cancellation, re-entrancy, clear |
| `ether-core` | `SinkLoggerTest` | 6 | level filtering, record fields, throwable attachment, derived loggers |
| `ether-core` | `FrameTimerTest` | 6 | baseline, deltas, accumulation, derived seconds/frame ratio, reset, monotonic source |
| `ether-testing` | `FloatAssertionsTest` | 4 | tolerance bounds, failure reporting, NaN equality |
| `ether-testing` | `FakeTimeSourceTest` | 2 | initial value, accumulated advancement |
| `ether-testing` | `RecordingLogSinkTest` | 3 | record capture, level filtering, fragment search, clear |
| `ether-render-api` | `RenderApiContractTest` | 20 | buffer/texture/sampler/shader descriptor validation, mip bounds, format-usage pairing, vertex layout, pipeline targets, pass attachments, viewport/scissor, capabilities, limits |
| `ether-render-core` | `GraphicsDeviceTest` | 10 | resource creation and counts, generational invalidation, cross-device handles, idempotent close, backend availability and capability checks, single-use command buffers |
| `ether-render-core` | `ValidationTest` | 16 | buffer/texture/sampler/shader/pipeline validation against reported capabilities and limits |
| `ether-render-core` | `CommandEncoderTest` | 26 | pass pairing, attachment usage/format/dimensions/sample counts, pipeline compatibility, vertex/index ranges, draw budget, instancing gate, copy pitch/range/mip/usage, recorded command sequence |
| `ether-render-core` | `DeferredFreeTest` | 7 | immediate free, deferred free until submission completion, multiple submissions, rejection after destroy, close drain |
| `ether-render-core` | `RenderGraphTest` | 9 | ordered recording, imports, unknown reads, feedback loops, duplicate/blank names, device scoping, attachment limits |
| `ether-render-core` | `BackendSelectionTest` | 8 | preference order, unavailable backends, fallback, duplicate registration, unregister, device creation |
| `ether-render-core` | `DocumentationExampleTest` | 1 | executes the headless rendering example published in `README.md` |
| `ether-render-null` | `NullBackendTest` | 8 | synthetic capabilities, resource lifetime, shader-stage checks, automatic/manual completion, unknown tokens, close |

Total: **197 tests** (42 math + 41 core + 9 test utilities + 20 render API + 77 render core +
8 null backend). The Linux x64 native run covers the same suite plus the 3 `ether-render-vulkan`
real-device smoke tests, for 200 tests.

## Running tests

```bash
./kotlin test                 # all runnable targets
./kotlin test -p jvm          # JVM only
./kotlin test -p linuxX64     # Linux native only
./kotlin test -p wasmJs       # headless Wasm
./kotlin test --include-module=ether-core --include-classes=dev.instrum.ether.core.event.*
./kotlin test --include-module=ether-math --include-test=dev.instrum.ether.math.Mat4Test.inverseUndoesTheOriginalTransform
```

Test filters require an explicit `--include-module` (or `--exclude-module`) selection; without it the
tool reports an error rather than searching every module.

## Verified results

On the current Linux host (2026-11-07):

| Target | Result |
|---|---|
| JVM | 197/197 pass |
| `linuxX64` | 197/197 pass |
| `wasmJs` | 197/197 pass (headless), 0 failed |
| `android` | Compiles; tests not executed (requires emulator/device) |
| `mingwX64` | Compiles; tests not executed |
| iOS | Not built (requires macOS) |

The JVM run reports a per-module summary that sums to 197 successful tests and 0 failures. The
Linux native run starts 197 test methods, reports no failed test, and exits zero. The Wasm run
reports 197 `Passed` lines and 0 `Failed` lines, and exits zero.

Two output caveats apply to non-JVM runners: the native runner can lose individual `Passed` lines
when its output is redirected (the count varies between runs), and the Wasm runner interleaves
container lines, so `Started` lines overstate the test count. Verify with the exit code and the
`Failed` count, and re-run any suspected failure in isolation.

## The `ether-testing` module

`ether-testing` provides utilities for tests in other modules:

```kotlin
import dev.instrum.ether.testing.FakeTimeSource
import dev.instrum.ether.testing.RecordingLogSink
import dev.instrum.ether.testing.assertFloatEquals

val clock = FakeTimeSource()
val sink = RecordingLogSink()

logger.info("hello")
assertTrue(sink.contains(LogLevel.Info, "hello"))

clock.advance(16_666_666)
assertEquals(16_666_666, clock.nowNanos())
assertFloatEquals(1f, 1.000001f)
```

| Utility | Purpose |
|---|---|
| `FakeTimeSource` | Deterministic nanosecond clock with `advance()` |
| `RecordingLogSink` | Captures `LogRecord`s for assertions |
| `assertFloatEquals` | Tolerance-based float assertion that throws `AssertionError` |
| `assertFloatEqualsIncludingNaN` | Same, treating `NaN` as equal to `NaN` |

`ether-testing` deliberately does not depend on `kotlin.test` in its main source set. The assertion
helpers throw `AssertionError` directly, so the module can be consumed by any test framework and by
tools that are not tests. Do not add `kotlin.test` to its main dependencies.

## The rendering test seam

`ether-render-core` tests drive the public device against the null backend through a fixture that
exposes both the device and the backend device for inspection:

```kotlin
val fixture = createNullTestDevice(NullCompletionMode.Manual)
val device = fixture.device

// record and submit work that references fixture.device resources
fixture.device.submit(commandBuffer)

// the submission is still pending: referenced resources must stay alive
fixture.backend.completeAllSubmissions()
assertEquals(1, fixture.device.pollCompletedSubmissions())
```

`NullCompletionMode.Manual` keeps submissions pending until the test completes them, which is how
deferred free is verified deterministically. `NullCompletionMode.Automatic` completes a submission
at submit time for tests that do not exercise lifetime. The fixture lives in
`modules/ether-render-core/test/.../RenderTestSupport.kt` and is not public API.

## Test design rules

1. **Deterministic.** No wall-clock reads, no real sleeps, no network, no GPU. Use `FakeTimeSource`.
2. **Isolated.** Each test creates its own engine, bus, logger, and sink. No shared mutable state.
3. **Behavioral.** Assert observable behavior and state transitions, not implementation details.
4. **Boundary-focused.** Test zero vectors, singular matrices, empty inputs, clamping, double
   shutdown, cancellation during dispatch, and stale handles.
5. **No flaky tolerances.** Use the smallest tolerance that reflects float error; do not hide real
   bugs with `1e-1`.
6. **Every bug gets a regression test.** The Mat4 inverse and monotonic-clock bugs found in Phase 1
   are covered by tests.
7. **Platform-specific code needs a platform test or an explicit "untested" note.** Do not imply
   coverage that does not exist.

## What is not tested yet

| Area | Reason | Planned coverage |
|---|---|---|
| Real GPU execution (Linux x64) | Covered by three `ether-render-vulkan` smoke tests that draw and read back a triangle on the real device (verified on RADV PHOENIX/Mesa). These tests fail honestly when no Vulkan device exists. | Android device/emulator and WebGL2 browser coverage remain open |
| Visual output | No surface, swap-chain, or presentation layer | Offscreen render readback, then a windowed sample |
| Render graph compilation | Pass culling, barrier derivation, and transient resources are not implemented | Unit tests when the compiler exists |
| Shader compilation | Shader modules carry opaque bytes only | Offline compilation tests with SPIR-V fixtures |
| Window/input | No platform layer | Phase 3 platform tests |
| Android runtime | No emulator in the loop | Android CI job |
| Windows runtime | No Windows host | Windows CI job |
| iOS | Requires macOS/Xcode | macOS CI job |
| Wasm rendering | No web backend | Browser smoke test |
| Performance | No real backend or allocator | Benchmarks after a real backend lands |

## Adding tests

1. Create the test in the module's `test/` directory, mirroring the production package.
2. Use `kotlin.test` assertions (`assertEquals`, `assertTrue`, `assertFailsWith`).
3. Use `FakeTimeSource`/`RecordingLogSink` instead of real time and console output.
4. Run `./kotlin test -p jvm` first, then `./kotlin test` for native/Wasm.
5. If the test needs a new dependency, add it to `test-dependencies` in `module.yaml`, not to main
   dependencies.
6. Keep test names descriptive: `cancellingDuringDispatchDoesNotDisturbIteration`, not `test3`.

## CI expectations

A pull request is mergeable when:

- `./kotlin build` succeeds;
- `./kotlin test` succeeds on JVM and Linux native;
- `./kotlin test -p wasmJs` exits zero;
- new behavior has tests;
- no new warnings are introduced;
- public API has KDoc and `explicitApi` passes.

Once a real backend module exists, its smoke test becomes required. The null backend already runs
the shared validation path, but no rendering claim is valid until a real backend draws a frame in
CI.

<div align="center">
  <img src="assets/ether-icon.png" alt="Ether logo" width="176" />
  <p>
    <a href="https://github.com/kysawa-junior/ether"><img src="https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin Multiplatform" /></a>
    <a href="https://github.com/kysawa-junior/ether/blob/main/LICENSE"><img src="https://img.shields.io/github/license/kysawa-junior/ether?color=0EA5E9" alt="Apache 2.0 license" /></a>
    <img src="https://img.shields.io/badge/Engine-2D%20%2F%203D-2563EB" alt="2D and 3D engine" />
    <img src="https://img.shields.io/badge/Status-In%20Development-F59E0B" alt="In development" />
  </p>
  <p>
    <a href="#-overview">Overview</a> ·
    <a href="#-modules">Modules</a> ·
    <a href="#-quick-start">Quick start</a> ·
    <a href="#-platform-status">Platform status</a> ·
    <a href="#-roadmap">Roadmap</a>
  </p>
</div>

---


Ether is a 2D/3D game engine and rendering library for Kotlin Multiplatform. It is built around
explicit lifecycles, capability-based rendering, and a hybrid hierarchical scene + ECS model.

**Status: Phase 2 rendering foundation plus the map engine vertical. The Linux x64 native and JVM
(LWJGL) Vulkan backends are implemented and smoke-tested on real hardware (AMD RADV PHOENIX,
Mesa); WebGL2 and window presentation are the next milestone.** The repository contains a tested
math library, the core engine services, a portable render API, a validated rendering core with a
linear render graph, a headless null backend, two Vulkan backends (linuxX64 native, JVM via
LWJGL), the versioned cartographic contract `ether-map-data`, and the `ether-map` engine vertical
(OSM reading, ENU projection, mesh building, scene, orbit camera with the 2D/3D mode policy,
streaming, render queue, map runtime).

## What exists today

| Module | Contents | Status |
|---|---|---|
| `ether-math` | `Vec2`/`Vec3`/`Vec4`, `Quat`, `Mat3`/`Mat4`, `Transform`, `Aabb`, `BoundingSphere` | Implemented, 42 tests |
| `ether-core` | Engine lifecycle, frame timing, config, typed events, logging, error types | Implemented, 41 tests |
| `ether-testing` | Deterministic clock, recording log sink, float assertions | Implemented, 9 tests |
| `ether-render-api` | Typed handles, descriptors, capabilities/limits, render passes, commands, backend HAL | Implemented, 20 tests |
| `ether-render-core` | Device facade, generational registry, validation, encoder/translator, deferred free, linear render graph, backend selection | Implemented, 77 tests |
| `ether-render-null` | Headless backend with deterministic automatic/manual submission completion | Implemented, 8 tests |
| `ether-render-vulkan` | Real GPU backend (linuxX64): loader via `dlopen`, device probe, staging buffers, offscreen rasterization, readback | Implemented on Linux x64, 3 real-device smoke tests |
| `ether-render-lwjgl` | JVM Vulkan backend: LWJGL 3.4.3 bindings, packaged native bootstrap, capability probe | Implemented (ADR-0022); frame smoke test pending |
| `ether-map-data` | `urban-map-package` v1 contract: manifest, tiles, features, validation, JSON decoding | Implemented |
| `ether-map` | Map engine vertical: OSM reader/mapper, ENU + XYZ tiles, triangulation, mesh builders, scene/graph/raycast/fleet, orbit camera + `MapModePolicy`, streaming (LRU + package loader), render queue, recorder renderer, `MapRuntime`, `MapEngine` | Implemented, 89 unique tests |

The full suite is **306 tests** on JVM, **306** on linuxX64 and **303** on wasmJs (89 unique tests
in `ether-map`); 0 failures, measured 2026-11-10. The Linux x64 native run adds the 3 Vulkan smoke
tests.

Design decisions and research are documented:

- [Engine research](docs/architecture/ENGINE_RESEARCH.md) — source-level study of Godot, Bevy,
  O3DE, Filament, bgfx, wgpu, KorGE, and Kotlin Multiplatform graphics constraints.
- [Architecture decisions](docs/architecture/ARCHITECTURE_DECISIONS.md) — ADRs with context,
  consequences, and rejected alternatives.
- [Engine architecture](docs/architecture/ENGINE_ARCHITECTURE.md) — module topology, lifecycle,
  frame loop, events, and the proposed scene/ECS model.
- [Rendering architecture](docs/architecture/RENDERING_ARCHITECTURE.md) — implemented API/core/null
  foundation, render graph, resource lifetime, and the remaining backend plan.
- [Multiplatform strategy](docs/architecture/MULTIPLATFORM_STRATEGY.md) — targets, source sets,
  platform constraints, and validation status.
- [Building](docs/development/BUILDING.md) and [testing](docs/development/TESTING.md).

## Quick start

```kotlin
import dev.instrum.ether.core.Engine
import dev.instrum.ether.core.EngineState
import dev.instrum.ether.core.config.EngineConfig
import dev.instrum.ether.core.log.NoOpLogSink

fun main() {
    val engine = Engine.create(
        config = EngineConfig(applicationName = "Demo"),
        sink = NoOpLogSink,
    )
    engine.initialize()
    while (engine.currentState == EngineState.Running) {
        val timing = engine.update()
        // application systems consume timing.deltaSeconds
    }
    engine.shutdown()
}
```

The loop above is illustrative: there is no window or GPU renderer, so an application must provide
its own exit condition and pacing. Frame-rate capping is configured but not yet enforced.

## Headless rendering for now

The rendering core is usable without a GPU. The null backend implements the same HAL as a real
backend and records every validated command:

```kotlin
import dev.instrum.ether.render.api.BackendDeviceDescriptor
import dev.instrum.ether.render.api.BufferDescriptor
import dev.instrum.ether.render.api.BufferUsage
import dev.instrum.ether.render.core.RenderBackendRegistry
import dev.instrum.ether.render.nullbackend.NullBackend

val backends = RenderBackendRegistry()
backends.register(NullBackend())
val device = backends.createDevice(BackendDeviceDescriptor(label = "headless"))

val buffer = device.createBuffer(
    BufferDescriptor(sizeBytes = 1024, usage = setOf(BufferUsage.Uniform)),
)
device.destroyBuffer(buffer)
device.close()
```

The rendering foundation is covered by 105 tests (20 API, 77 core, 8 null backend).
Those validate descriptors and command state without rasterizing anything. The 3 Linux x64
Vulkan smoke tests rasterize a built-in triangle on the real device (verified on an AMD
RADV PHOENIX GPU) and read the exact texels back; 3 of 3 pass.

## Platform matrix

| Target | Build | Tests |
|---|---|---|
| JVM | Validated | Pass (197) |
| Android | Validated (compile) | Not run (needs emulator/device) |
| Linux x64 | Validated | Pass (200, incl. 3 Vulkan smoke tests) |
| Vulkan on Linux x64 | Validated | Pass (3 real-device smoke tests) |
| Windows x64 (`mingwX64`) | Validated (compile) | Not run |
| WebAssembly (`wasmJs`) | Validated | Pass (197, headless) |
| iOS arm64 / simulator | Declared | Not built (requires macOS) |

"Validated" means the target was built on the current Linux host. See
[ADR-0017](docs/architecture/ARCHITECTURE_DECISIONS.md) for the support-claim policy.

## Build and test

```bash
./kotlin build
./kotlin test
./kotlin test -p wasmJs
```

The build uses [JetBrains Amper](https://github.com/JetBrains/kotlin) via the `./kotlin` wrapper. No
Gradle, JDK, or Kotlin installation is required; the wrapper provisions the toolchain. See
[BUILDING.md](docs/development/BUILDING.md).

## Design principles

1. **No fake implementations.** A module exists only when it contains real, tested code.
2. **Capability-based rendering.** Features are gated by reported capabilities, never backend type
   checks.
3. **Explicit resource lifetime.** GPU resources are destroyed explicitly; memory is not left to the
   GC.
4. **Opaque handles.** No raw backend objects in the public API.
5. **Honest support claims.** A platform or backend is "supported" only after CI builds and runs it.
6. **Deterministic core.** Time, events, and lifecycle are testable without a GPU or a clock.

## Roadmap

| Phase | Scope |
|---|---|
| 1 | Math, core lifecycle, time, events, logging, test utilities — **complete** |
| 2 | Render API/core, null backend, linear render graph — **complete**; Vulkan, WebGL2, shader pipeline, first lit scene — pending |
| 3 | Scene graph, ECS, assets, window/input |
| 4+ | Metal, WebGPU, audio, physics, animation, tooling, editor |

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) and the architecture documents before opening a pull
request. New behavior requires tests; new public API requires KDoc and `explicitApi` compliance.

## License

Apache License 2.0. See [LICENSE](LICENSE).

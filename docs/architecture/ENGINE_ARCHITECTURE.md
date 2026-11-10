# Engine Architecture

## Status

Phase 1 (Foundation) is implemented and validated on the current Linux host. The Phase 2 rendering
foundation — `ether-render-api`, `ether-render-core`, and `ether-render-null` — is also implemented
and validated across JVM, Linux x64, and Wasm tests. Real GPU backends, window/surface orchestration,
and the scene layer are not implemented. This document distinguishes **implemented**, **proposed**,
and **not started** explicitly.

| Area | Status |
|---|---|
| Math library | Implemented, tested |
| Core lifecycle, time, events, logging, config | Implemented, tested |
| Test utilities | Implemented, tested |
| Portable render API | Implemented, tested |
| Rendering core, validation, linear render graph | Implemented, tested |
| Null rendering backend | Implemented, tested |
| Real GPU backends (Vulkan/WebGL2/Metal/WebGPU) | Not started |
| Window/platform layer | Not started |
| Hierarchical scene | Proposed (Phase 3) |
| Hybrid ECS | Proposed (Phase 3) |
| Asset pipeline | Proposed (Phase 3) |

## Goals

Ether is a production-quality 2D/3D game engine library for Kotlin Multiplatform:

- deterministic, explicit lifecycles for the engine and GPU resources;
- a rendering abstraction that is capability-based and backend-agnostic;
- a hybrid scene/ECS model that supports both editor-style hierarchies and data-oriented systems;
- no platform or backend claims without verification;
- a small, documented public API that can be published and consumed by applications and tools.

## Non-goals

Phase 1 deliberately shipped no renderer, window, input, audio, physics, or networking. Phase 2 added
a portable rendering foundation with no real GPU backend; the remaining items are still out of scope.

- No window, input, audio, physics, or networking.
- No editor.
- No hot reload or scripting.
- No performance claims.

## Module topology

### Implemented modules

| Module | Package | Responsibility |
|---|---|---|
| `ether-math` | `dev.instrum.ether.math` | Vectors, quaternions, matrices, transforms, bounds, scalar utilities |
| `ether-core` | `dev.instrum.ether.core` | Lifecycle, frame timing, configuration, events, logging, error types |
| `ether-testing` | `dev.instrum.ether.testing` | Deterministic clock, recording log sink, float assertions |
| `ether-render-api` | `dev.instrum.ether.render.api` | Typed handles, descriptors, capabilities/limits, commands, backend HAL |
| `ether-render-core` | `dev.instrum.ether.render.core` | Device facade, validation, registry, encoder/translator, linear graph, backend selection |
| `ether-render-null` | `dev.instrum.ether.render.nullbackend` | Headless backend with deterministic submission completion |

Dependencies are acyclic:

```
ether-testing ──> ether-core ──> ether-math
ether-render-null ──> ether-render-api
ether-render-core ──> ether-render-api
ether-render-core ──> ether-core ──> ether-math
```

`ether-math` has no engine dependencies. `ether-core` depends on math for future transform-aware
services but does not yet expose it transitively (`exported: false`). `ether-core`'s **test** source
set depends on `ether-testing`; `ether-render-core`'s **test** source set additionally depends on
`ether-render-null`. Those edges are test-scope only and do not create production dependency cycles.

`ether-render-core` exports both `ether-render-api` and `ether-core` so consumers of the public
device facade can see handles, capabilities, and engine exceptions. `ether-render-null` depends only
on the portable API, so it can be used without the core.

### Planned modules

These are documented so the architecture is coherent, but they do not exist yet. Creating them
before they contain real code is prohibited by ADR-0003.

| Module | Phase | Responsibility |
|---|---|---|
| `ether-render-vulkan` | 2 | Vulkan backend (linuxX64, Android) |
| `ether-render-webgl2` | 2 | WebGL2 backend (wasmJs/js) |
| `ether-render-metal` | 2+ | Metal backend (macOS/iOS; requires macOS CI) |
| `ether-render-webgpu` | later | Optional WebGPU backend |
| `ether-platform` | 3 | Window, input, file system, display info |
| `ether-scene` | 3 | Scene graph, transform hierarchy, render extraction |
| `ether-ecs` | 3 | Entity IDs, sparse-set/table component storage, systems |
| `ether-assets` | 3 | Asset IDs, catalogs, import pipeline, async loading |
| `ether-audio` | 4+ | Audio device and mixing |
| `ether-physics` | 4+ | Physics abstraction and backend integration |

### Dependency rules

1. `ether-math` is the leaf. It must not depend on any other Ether module.
2. `ether-core` may depend on `ether-math`; it must not depend on rendering, scene, or platform.
3. Rendering API types must not depend on scene or platform types.
4. Backends depend on the rendering API and core, never the reverse.
5. `ether-scene` depends on math, core, and render API; it does not depend on a concrete backend.
6. Applications depend on modules, not on internal packages. Anything marked `internal` is not API.

## Engine lifecycle

`Engine` is an open class with a protected constructor for subclassing. `Engine.create(...)` builds
the default implementation; subclasses call the protected constructor directly.

```
Created
   │ initialize()
   ▼
Initializing ── failure ──> Failed
   │ success
   ▼
Running ── pause() ──> Paused
   ▲                     │
   └──── resume() ───────┘
   │ shutdown()
   ▼
Stopping ── failure ──> Failed
   │ success
   ▼
Stopped
```

Rules enforced in code:

- `update()` is legal only in `Running`.
- `pause()` is legal only in `Running`; `resume()` only in `Paused`.
- `initialize()` is legal only in `Created`.
- `shutdown()` is legal from any state except `Stopped` and `Stopping`.
- Exceptions from `onInitialize` are wrapped in `EngineInitializationException`; the engine moves to
  `Failed`.
- Exceptions from `onShutdown` move the engine to `Failed`.

Subclass hooks:

```kotlin
protected open fun onInitialize()
protected open fun onUpdate(timing: FrameTiming)
protected open fun onShutdown()
```

The hooks are intentionally minimal. Later milestones will introduce a `GameLoop`/`Application`
layer that owns windows and rendering, while `Engine` remains the lifecycle spine.

## Frame loop

```kotlin
val engine = Engine.create(EngineConfig(applicationName = "Demo"))
engine.initialize()
while (engine.currentState == EngineState.Running) {
    val timing = engine.update()
    // application systems consume timing
}
engine.shutdown()
```

`Engine.update()`:

1. validates state;
2. asks `FrameTimer` for raw timing;
3. clamps `deltaNanos` to `EngineConfig.maximumDeltaSeconds`;
4. stores `lastFrameTiming`;
5. invokes `onUpdate`.

Clamping prevents the "spiral of death" after a debugger pause or a long asset load. The first
frame always reports a zero delta because it establishes the time baseline.

## Time model

- `TimeSource` is a `fun interface` returning nanoseconds from an unspecified epoch. Only
  differences are meaningful.
- `MonotonicTimeSource` anchors once at object initialization and uses
  `kotlin.time.TimeSource.Monotonic`; it never goes backwards.
- `FrameTimer` accumulates elapsed time and frame indices. It does not clamp; clamping is an engine
  policy decision.
- `FrameTiming` exposes seconds and a 60 Hz frame ratio for animation scaling.
- Wall-clock timestamps for logs use `kotlin.time.Clock.System`, not the deprecated JVM
  `System.currentTimeMillis`.

## Events

`EventBus` is synchronous and typed:

```kotlin
class SceneLoaded(val name: String) : Event

val subscription = engine.events.subscribe(SceneLoaded::class) { event ->
    println(event.name)
}
engine.events.publish(SceneLoaded("level-1"))
subscription.cancel()
```

Properties:

- exact runtime class matching;
- registration-order delivery;
- snapshot iteration, so listeners may cancel during dispatch;
- re-entrant publishing is delivered synchronously;
- single-thread ownership, no internal synchronization;
- `publish` returns the number of listeners invoked.

Cross-thread events will be introduced with an explicit queue drained by the main loop, not by
making `EventBus` thread-safe.

## Logging

- `Logger` is the facade: `trace`, `debug`, `info`, `warn`, `error`, `log`.
- `SinkLogger` filters by threshold, stamps records with wall-clock milliseconds, and forwards to a
  `LogSink`.
- `LogSink` is a `fun interface`; implementations own their thread-safety policy.
- `ConsoleLogSink` delegates to an `expect fun writeToConsole`, implemented with `println` on
  JVM/Android/native and browser console interop on Wasm.
- `NoOpLogSink` supports tests and headless tools.
- `SinkLogger.withTag` and `withLevel` derive loggers sharing the same sink and clock.

Log records carry `level`, `tag`, `message`, `throwable`, and `timestampMillis`. Logging must not
throw into engine code; a failing sink is a sink bug, not an engine failure.

## Configuration

`EngineConfig` is immutable:

| Property | Default | Constraint |
|---|---|---|
| `applicationName` | `"Ether Application"` | none |
| `logLevel` | `Info` | valid enum |
| `targetFramesPerSecond` | `0` (uncapped) | `>= 0` |
| `maximumDeltaSeconds` | `0.25` | `> 0` |
| `enableValidation` | `true` | none |

Derived: `targetFrameIntervalNanos` is `null` when uncapped, otherwise `1_000_000_000 / fps`.

Frame-rate capping is **not yet enforced** by `Engine.update()`. The configuration is parsed and
tested; the sleep/pacing implementation belongs to the platform layer, because only that layer knows
about vsync and timers. This is a known Phase 1 limitation, not a silent feature.

## Error model

`EtherException` is the base type. `EngineInitializationException` wraps initialization failures
with the original cause. `InvalidEngineStateException` reports lifecycle misuse.
`MissingServiceException` is reserved for service lookup (not used yet).

Errors are exceptions, not sentinel values. Rendering validation failures are non-fatal
exceptions scoped to the operation that violated the contract; fatal device loss remains fatal when
a real backend is added.

## Rendering foundation (implemented)

The portable renderer is split into four layers. Three exist today:

```
application / future ether-scene
        │
        ▼
ether-render-api        handles, descriptors, capabilities, commands, HAL
        │
        ▼
ether-render-core       device facade, validation, registry, encoder, linear graph
        │
        ▼
ether-render-null       headless HAL implementation (implemented)
ether-render-vulkan     real GPU backend (Linux x64, smoke-tested)
ether-render-webgl2     real GPU backend (planned)
```

Properties enforced by the current implementation:

- **Opaque handles.** `BufferHandle`, `TextureHandle`, `SamplerHandle`, `ShaderModuleHandle`, and
  `RenderPipelineHandle` are typed value classes. Raw backend tokens never reach callers.
- **Generational validation.** The core packs an index and generation into each handle. Stale,
  destroyed, or cross-device handles fail with `InvalidRenderResourceException`.
- **Capability gating.** Descriptor and command validation consults `GraphicsCapabilities` for
  features (storage buffers/textures, anisotropy, instancing, compute) and numeric limits before
  calling a backend.
- **Record, translate, submit.** `CommandEncoder` records portable commands with eager validation.
  At submit, `CommandTranslator` resolves handles to backend tokens and produces
  `BackendCommand` lists. The backend never sees the core's handle tables.
- **Submission-token deferred free.** A resource destroyed while referenced by a submission stays
  alive until that submission reports completion through `RenderBackendDevice.isSubmissionComplete`.
  Resources destroyed with no in-flight references are freed immediately.
- **Linear render graph.** Passes declare attachments and read sets; the builder rejects unknown
  reads, feedback loops, duplicate names, and blank names. Passes execute in declaration order.
- **Runtime backend registry.** `RenderBackendRegistry` selects the first available backend from a
  caller-supplied preference (`Vulkan → Metal → WebGl2 → WebGpu → Null` by default), then falls back
  to any other registered backend.

The null backend is the only backend. It validates and records the same command stream but executes
no GPU work, so it cannot present an image. Surface/swap-chain orchestration, shader compilation,
bind groups, compute passes, indirect draws, and graph compilation (culling, reordering, barriers,
transient aliasing) are not implemented. See
[RENDERING_ARCHITECTURE.md](RENDERING_ARCHITECTURE.md) for the detailed contract.

## Hybrid scene and ECS (proposed)

The scene/ECS split is the most important architectural decision after the renderer. The design:

### Identity

`EntityId` is an index plus generation. Scene nodes and ECS components are keyed by the same ID.
Generation bumps on despawn; stale IDs fail validation.

### Scene layer

`Scene` owns:

- parent/child links (single parent, ordered children);
- local transform, cached world transform, dirty flag;
- visibility and active flags;
- node lifetime and destruction queue;
- optional name/tag metadata for debugging and editor use.

Transform propagation is top-down. A node is marked dirty when its local transform or parent
changes; world transforms are recomputed once per frame before render extraction. The scene is the
single owner of hierarchy; components never store parent pointers.

### ECS layer

`World` owns component storage:

- sparse sets for hot components such as transform inputs, velocity, and render visibility;
- table/archetype storage for cold or bulk components;
- system registration and ordered stages.

Systems do not mutate hierarchy directly. Structural changes (spawn/despawn/reparent) are queued
and applied at defined sync points, mirroring Bevy's deferred command pattern without its
parallel-executor complexity.

### Render extraction

Once per frame:

1. propagate dirty transforms;
2. walk visible nodes;
3. resolve meshes, materials, lights, and cameras;
4. append to a flat, reusable `RenderList`;
5. hand the list to the renderer.

Extraction is allocation-conscious and runs on the main thread. The renderer must not walk the scene
graph.

## Asset model (proposed)

- `AssetId` is a 64-bit stable identity generated at import time.
- An `AssetCatalog` maps `AssetId` to source location, type, and import metadata.
- Runtime `AssetHandle<T>` values reference `AssetId` and a generation.
- Loading is asynchronous through coroutines; completion is delivered on the main thread.
- Asset state changes are events (`Loaded`, `Reloaded`, `Unloaded`, `Failed`).
- The renderer consumes engine-native assets, never source formats.

## Threading model

Phase 1 and the Phase 2 rendering foundation are single-threaded by construction:

- `Engine`, `EventBus`, and `SinkLogger` are owned by one thread.
- `GraphicsDevice`, its handle tables, command encoders, and the null backend are owned by one
  thread; there is no internal synchronization.
- `MonotonicTimeSource` is safe to read from multiple threads; it holds an immutable mark.
- No shared mutable global state exists. `RenderBackendRegistry` is instance-scoped.

Future model:

- main thread: lifecycle, scene, ECS systems, render extraction, command recording;
- render thread: backend submission and presentation (where the platform allows it);
- worker threads: asset I/O and decoding, with results delivered through a queue;
- Wasm: no shared-memory threads by default; the design must remain correct when everything runs on
  one thread.

## Resource ownership

| Resource | Owner | Release |
|---|---|---|
| `Engine` | application | `shutdown()` |
| `EventBus` | `Engine` | with engine |
| `Logger`/`LogSink` | caller/engine | caller-owned; engine does not close sinks |
| Math values | value types | GC |
| GPU resources | `GraphicsDevice` registry | explicit `destroy()`, deferred until referencing submissions complete |
| Scene nodes (future) | `Scene` | despawn queue, recursive by policy |
| Assets (future) | `AssetServer`/catalog | handle release + unload policy |

GPU resources are not managed by the Kotlin GC. The device tracks generational handles and pending
submission tokens, and reports use-after-destroy during validation.

## Extension points

- `Engine` subclass hooks (`onInitialize`, `onUpdate`, `onShutdown`).
- `LogSink` implementations.
- `TimeSource` implementations (tests, deterministic replay).
- Events for decoupled systems.
- Custom rendering backends registered through `RenderBackendRegistry`.
- Future `GameLoop`/platform host replacing the application's `while` loop.

## Roadmap

### Phase 1 — Foundation (complete)

- math library;
- core lifecycle, time, config, events, logging;
- test utilities;
- build and test validation.

### Phase 2 — Rendering foundation

Complete:

- `ether-render-api` and `ether-render-core`;
- null backend with validation, command recording, and deterministic completion;
- generational resource registry and capability validation;
- command encoder, translator, and submission-token deferred free;
- linear render graph.

Still to implement:

- Vulkan backend on linuxX64 (then Android);
- WebGL2 backend on wasmJs;
- surface/swap-chain and frame orchestration;
- shader pipeline and bind-group model;
- graph compilation (order, culling, barriers, transient aliasing);
- first triangle, then a lit 3D scene.

### Phase 3 — Scene, ECS, assets, platform

- entity IDs and component storage;
- scene graph and transform propagation;
- window/input layer;
- asset catalog and async loading;
- render extraction pipeline.

### Phase 4+ — Production features

- Metal backend with macOS CI;
- audio, physics, animation, scripting;
- editor/tooling;
- performance and memory budgets.

## Known limitations

- The only rendering backend is the null backend: it validates and records commands but produces no
  visual output.
- No window, surface, swap-chain, or presentation layer exists.
- The render graph is linear. Pass culling, topological reordering, barrier derivation, transient
  resource pooling, and aliasing are not implemented.
- No bind-group/descriptor-set API, compute pass, indirect draw recording, or texture-to-buffer copy
  exists yet.
- Frame-rate capping is configured but not enforced.
- iOS targets compile only on macOS; they are unvalidated here.
- `mingwX64` is compile-validated, not runtime-validated.
- Wasm tests run headless through the Amper test runner; no web GPU backend means browser rendering
  is untested.
- `ether-testing` is a test-support module; production modules must not depend on it.
- Direct tests cover the test utilities; they are also used by `ether-core` tests.

# Architecture Decisions

This document records decisions that shape Ether. Each entry states the context, the decision, its
consequences, and the alternatives that were rejected. Status values:

- **Accepted** — in force.
- **Proposed** — design agreed, implementation not started.
- **Superseded** — replaced by a later decision.

---

## ADR-0001: Use JetBrains Amper as the build system

**Status:** Accepted

**Context.** The repository was created from an Amper template (`./kotlin` wrapper, version 0.13.0)
and contains no Gradle build. Amper is a Kotlin-first build tool with first-class Kotlin
Multiplatform support, declarative `module.yaml` files, and a small wrapper. The alternative is to
convert the repository to Gradle, which is the ecosystem default and has broader IDE/plugin
support.

**Decision.** Keep Amper. Convert the single template module into a multi-module project with a root
`project.yaml` listing modules, and per-module `module.yaml` files.

**Consequences.**

- Build commands are `./kotlin build`, `./kotlin test`, and related Amper tasks.
- Dependency notation and source-set conventions follow Amper, not Gradle.
- The project avoids Gradle Kotlin DSL and version catalogs; versions are declared in
  `module.yaml` or resolved by the toolchain.
- Documentation must be explicit about Amper syntax, because most Kotlin developers expect Gradle.

**Alternatives rejected.**

- *Convert to Gradle.* Rejected because the repository is Amper-native and conversion would add
  churn without a functional benefit at this stage. It also risks losing the verified multi-target
  configuration.

---

## ADR-0002: Kotlin Multiplatform with an explicit target matrix

**Status:** Accepted (iOS targets declared, not validated on this host)

**Context.** Ether must run on desktop, Android, iOS, and the web. Kotlin Multiplatform supports all
of these, but support quality and toolchain availability differ. The development host is Linux, so
Apple targets cannot be compiled or tested locally.

**Decision.** Declare the target matrix:

```
jvm, android, linuxX64, mingwX64, wasmJs, iosArm64, iosSimulatorArm64
```

Every module declares the full matrix. Only targets that actually build on the host are claimed as
validated.

**Consequences.**

- Common code must avoid JVM-only and platform-only APIs.
- `expect`/`actual` declarations are required for logging, time, and future windowing.
- iOS compilation is unverified until macOS CI exists. Documentation and release notes must say so.
- `mingwX64` compiles but is not runtime-tested.

**Alternatives rejected.**

- *JVM-first with later porting.* Rejected because platform abstractions are cheapest to design
  before the renderer exists.
- *Declare only currently validated targets.* Rejected because the target matrix is a product
  requirement; the honest approach is to declare targets and mark validation status.

---

## ADR-0003: Create only modules that contain real code

**Status:** Accepted

**Context.** A common failure mode in engine projects is a directory tree full of empty modules
("audio", "physics", "networking") that imply functionality that does not exist. The user
explicitly rejected fake implementations and TODO-driven architecture.

**Decision.** A module is created only when its first real implementation and tests exist. Phase 1
shipped `ether-math`, `ether-core`, and `ether-testing`; Phase 2 added `ether-render-api`,
`ether-render-core`, and `ether-render-null` under the same rule. Planned modules are documented,
not scaffolded.

**Consequences.**

- The repository is smaller and every module builds.
- The documentation must carry the roadmap for future modules.
- There is no placeholder API surface to stabilize prematurely.

**Alternatives rejected.**

- *Create the full module skeleton now.* Rejected: empty modules would be indistinguishable from
  unfinished features and would force premature interface decisions.

---

## ADR-0004: Package, publication, and API surface conventions

**Status:** Accepted

**Context.** Ether is intended to be a publishable library, not an application. Kotlin libraries
need a stable package root, a publication identity, and a clear API surface.

**Decision.**

- Root package: `dev.instrum.ether`.
- Publication group: `dev.instrum.ether`; version `0.1.0`.
- `explicitApi: strict` on every module.
- Public API is documented with KDoc; internal helpers stay `internal`.
- Module artifact IDs follow `ether-<module>`.

**Consequences.**

- Public declarations require explicit visibility and return types.
- Accidental API leaks are caught at compile time.
- KDoc is part of the definition of done for public declarations.

**Alternatives rejected.**

- *Default (implicit) API mode.* Rejected because it leaks implementation details and makes
  compatibility management harder.

---

## ADR-0005: Mathematical conventions

**Status:** Accepted

**Context.** Rendering backends disagree about matrix layout, handedness, clip-space depth, and
texture coordinate origin. Retrofitting conventions after shaders and cameras exist is expensive.

**Decision.**

- Vectors and matrices use `Float`, immutable value types.
- Matrices are stored **column-major**, matching direct upload to Vulkan, Metal, and WebGL
  uniforms.
- The world coordinate system is **right-handed, Y-up**.
- Cameras look down **negative Z** in view space.
- Projection produces **clip-space depth `[0, 1]`**, matching Vulkan and Metal. WebGL's `[-1, 1]`
  depth is handled by a backend-specific projection adjustment.
- Quaternions are stored as `(x, y, z, w)`.
- Euler angles are `(pitch, yaw, roll)` around X, Y, Z, applied in that order.

**Consequences.**

- `Mat4` exposes `toFloatArray()` in column-major order for uniform upload.
- The WebGL2 backend must remap depth and may need to flip the Y axis depending on the framebuffer
  convention.
- Tests assert translation in elements 12-14 and depth mapping at the near/far planes.

**Alternatives rejected.**

- *Row-major storage.* Rejected: it forces a transpose on every GPU upload and is a common source
  of transposed-normal bugs.
- *`[-1, 1]` depth as the canonical convention.* Rejected: Vulkan and Metal are the primary 3D
  targets; WebGL is the outlier and can adapt.

---

## ADR-0006: Explicit engine lifecycle state machine

**Status:** Accepted (implemented)

**Context.** Engines fail when initialization, frame updates, and shutdown are called in arbitrary
orders. A boolean `running` flag cannot express partial initialization or failure.

**Decision.** Model lifecycle as an enum and enforce legal transitions:

```
Created -> Initializing -> Running <-> Paused -> Stopping -> Stopped
                |                                  ^
                +--------------> Failed -----------+
```

`initialize`, `update`, `pause`, `resume`, and `shutdown` validate the current state and throw
`InvalidEngineStateException` on misuse. Exceptions from lifecycle hooks move the engine to `Failed`
and propagate.

**Consequences.**

- Misuse fails fast and deterministically.
- `update()` is only legal in `Running`.
- Shutdown is allowed from `Created` (resources may be partially acquired) but not twice.
- The engine is not reusable after `Stopped`.

**Alternatives rejected.**

- *Idempotent lifecycle calls.* Rejected: double initialization and double shutdown usually mask
  bugs; making them explicit errors is safer.
- *Exception-free state checks.* Rejected for Phase 1; a result type can be added later for callers
  that prefer it.

---

## ADR-0007: Synchronous, type-keyed event bus with single-thread ownership

**Status:** Accepted (implemented)

**Context.** Engine systems need decoupled notifications (lifecycle, input, scene changes, asset
events). Async dispatch adds ordering and lifetime problems before the engine has an event loop.

**Decision.** `EventBus` dispatches synchronously on the publishing thread. Listeners are keyed by
the event's exact `KClass`. Publishing returns the number of listeners invoked. Listeners may cancel
during dispatch; iteration uses a snapshot. The bus is not synchronized and is owned by one thread.

**Consequences.**

- Event order is deterministic and easy to test.
- A slow listener blocks the frame; they must stay small.
- Cross-thread events require an explicit queue and drain step, which is future work.
- Exact-class matching avoids surprising superclass dispatch, but means marker interfaces do not
  receive events.

**Alternatives rejected.**

- *Async event bus.* Rejected for Phase 1: it would require a dispatcher, backpressure, and
  cancellation semantics before there is a frame scheduler.
- *String-keyed events.* Rejected: loses type safety.

---

## ADR-0008: Logging facade with platform console sink

**Status:** Accepted (implemented)

**Context.** Engine code needs leveled logging without depending on a platform logging library.
JVM, Android, native, and browser consoles all differ.

**Decision.** Define `Logger`, `LogSink`, `LogLevel`, and `LogRecord` in common code. `SinkLogger`
filters by level and timestamps records with a wall clock. `ConsoleLogSink` delegates to an
`expect fun writeToConsole(record)` implemented per platform (`println` on JVM/Android/native,
browser console on Wasm). `NoOpLogSink` supports tests.

**Consequences.**

- Core modules have no platform logging dependency.
- Timestamps use `kotlin.time.Clock`, not JVM `System.currentTimeMillis`.
- The Wasm sink needs explicit JavaScript interop and must keep `js(...)` calls to single-expression
  top-level functions.
- `SinkLogger` is not synchronized; sinks that are shared across threads must synchronize.

**Alternatives rejected.**

- *Use a third-party logging library.* Rejected for Phase 1: a small facade avoids a dependency and
  keeps the API under engine control.

---

## ADR-0009: Monotonic time anchored once, not per reading

**Status:** Accepted (implemented)

**Context.** Frame timing must not be affected by wall-clock changes. Kotlin 2.3+ deprecates
`kotlin.system.getTimeNanos` to an error, and the multiplatform replacement is
`kotlin.time.TimeSource.Monotonic`.

**Decision.** `MonotonicTimeSource` captures a single `TimeMark` when the object initializes and
returns `origin.elapsedNow()` for every reading. `FrameTimer` converts deltas to frame timing and
`Engine` clamps deltas to `EngineConfig.maximumDeltaSeconds`.

**Consequences.**

- The first frame reports a zero delta.
- A long pause produces a clamped delta rather than a huge simulation step.
- The clock never goes backwards, even if the wall clock changes.

**Alternatives rejected.**

- *Create a new `markNow()` per reading.* Rejected: it returns approximately zero and destroys frame
  timing. This was caught by a test.
- *Wall-clock time for frame deltas.* Rejected: NTP adjustments and suspend/resume produce negative
  or enormous deltas.

---

## ADR-0010: Hybrid hierarchical scene plus ECS

**Status:** Proposed (implementation starts in Phase 3)

**Context.** Pure scene graphs make data-oriented systems awkward. Pure ECS makes transforms,
parenting, and editor hierarchies awkward. Godot/O3DE are hierarchy-first; Bevy is ECS-first.

**Decision.** Use two cooperating layers keyed by the same entity ID:

- `Scene` owns a hierarchy of `SceneNode`s: parent/child links, local transform, cached world
  transform, visibility, active state, and lifetime.
- `World` owns component storage: sparse sets for hot components, table/archetype storage for cold
  components.
- The scene layer is the **only** owner of hierarchy. ECS components never store a second parent
  pointer.
- Render extraction walks the scene hierarchy, resolves world transforms, and produces a flat
  render list consumed by the renderer.

**Consequences.**

- Transform updates remain simple, top-down, and cache-friendly.
- Gameplay systems can query components without knowing about the hierarchy.
- Despawning must remove both the scene node and its components atomically.
- Extraction adds a per-frame pass; it should be batched and allocation-free.

**Alternatives rejected.**

- *Pure ECS with a parent component.* Rejected: requires repeated hierarchy queries and makes
  transform propagation fragile.
- *Pure scene graph with behavior attached to nodes.* Rejected: loses data-oriented iteration and
  makes systems harder to test.

---

## ADR-0011: Generational handles for entities and GPU resources

**Status:** Partially implemented (GPU handles in Phase 2; entity IDs in Phase 3)

**Context.** Index-only handles (bgfx's 16-bit index) cannot detect stale references after a slot is
reused. Raw object references make lifetime and threading unclear, especially for GPU resources.

**Decision.** Use value-type handles containing an index and a generation counter. The owner
validates both before resolving. Entity handles and GPU resource handles are distinct types, even
if they share a representation.

**Consequences.**

- Stale handles fail validation instead of silently addressing a new object.
- Handle size grows (two 32-bit fields is the default).
- The owner must retain generation tables and free lists.
- Handles are cheap to copy and safe to store in components and command buffers.

**Alternatives rejected.**

- *Index-only handles.* Rejected: stale references are a common and hard-to-debug failure.
- *Raw pointers/references.* Rejected: unsafe across frames and threads, and impossible to validate.

---

## ADR-0012: Capability-based rendering with features and numeric limits

**Status:** Accepted (implemented in Phase 2; null backend reports synthetic capabilities, Linux
x64 Vulkan reports live probed capabilities)

**Context.** Backends differ in features and limits. Engine code that assumes a desktop Vulkan
feature set breaks on WebGL2; code that targets the lowest common denominator leaves desktop
performance unused.

**Decision.** Every device reports:

- a set of `GraphicsFeature` flags (compute, timestamp queries, indirect draw, texture
  compression formats, float render targets, multi-draw, etc.);
- a `GraphicsLimits` data class with concrete numbers (texture size, array layers, bind groups,
  vertex attributes, uniform buffer size, draw-call budget);
- layout conventions (`clipSpaceDepthZeroToOne`, `framebufferOriginBottomLeft`).

Engine features are gated by capability checks, not by backend type checks. A backend may report a
feature only if it can actually execute it.

**Consequences.**

- WebGL2 is a first-class target with an explicit subset.
- Tests can use a null backend with synthetic limits.
- Capability negotiation must happen before resource creation and pipeline compilation.
- Documentation must list per-backend capabilities.

**Alternatives rejected.**

- *Backend-type checks (`if (backend == Vulkan)`).* Rejected: scattered conditionals and impossible
  to test without the real backend.
- *Lowest-common-denominator API.* Rejected: leaves performance and features unused on desktop.

---

## ADR-0013: Four-layer rendering architecture

**Status:** Accepted (API, core, and null backend implemented; Linux x64 Vulkan implemented and
smoke-tested; other real backends planned)

**Context.** wgpu demonstrates the value of separating portable types, public API, validation/core,
and backend HAL. Godot demonstrates the cost of two parallel resource namespaces.

**Decision.** Layering:

```
ether-render-api     Portable types: handles, descriptors, capabilities, enums
ether-render-core    Device facade, validation, resource registry, command encoding, render graph
ether-render-<api>   Backends: Vulkan, WebGL2, Metal, null
ether-scene          Render extraction and high-level camera/light/material data
```

There is one handle namespace per resource domain. A backend receives validated descriptors and
returns opaque tokens; the core maps tokens to typed handles. A backend never exposes native objects
to callers, and the core translates handles back to tokens only at submission.

**Consequences.**

- Backends implement a narrow HAL, not the whole public API.
- Validation is centralized and testable with the null backend.
- The API module has no platform dependencies.
- Splitting the core from the scene keeps rendering usable by tools and tests.

**Alternatives rejected.**

- *One rendering module with `expect`/`actual` backends.* Rejected: source-set explosion and no
  clean test seam.
- *Two namespaces (server + device RIDs).* Rejected: duplicated ownership and conversion bugs.

---

## ADR-0014: Render graph with transient resources and explicit passes

**Status:** Partially implemented (linear graph; compilation and transient resources planned)

**Context.** O3DE's RPI and Filament's post-process manager show that modern renderers compose
passes. Manual pass ordering and manual barrier placement do not scale.

**Decision.** Build a render graph:

- Passes declare resource reads/writes and pipeline state.
- The graph compiles a dependency order, culls unused passes, and derives barriers.
- Transient textures/buffers are allocated from a per-frame pool and aliased when lifetimes do not
  overlap.
- Persistent resources are explicit; transient resources never escape the frame.

The Phase 2 implementation is the linear subset: `RenderGraphBuilder` records passes with explicit
read sets, validates imported/produced dependencies, rejects feedback loops and duplicate names, and
executes in declaration order. Culling, topological ordering, barrier derivation, and transient
pooling/aliasing remain planned. The public pass API is intended to remain compatible.

**Consequences.**

- Adding a post-process pass does not require editing a monolithic render function.
- Barrier correctness is centralized.
- Debugging requires graph visualization and validation of resource states.
- The first implementation can be a linear pass list with the same interface; graph compilation is
  an internal optimization.

**Alternatives rejected.**

- *Immediate-mode draw calls only.* Rejected: no place to derive barriers or alias resources.
- *Hard-coded pass order.* Rejected: every feature addition becomes an `if` in the frame function.

---

## ADR-0015: Explicit GPU lifetime with submission-token deferred free

**Status:** Accepted (implemented in Phase 2 for the core, the null backend, and the Linux x64
Vulkan backend)

**Context.** GPU resources may still be referenced by commands that are in flight. Freeing
immediately causes use-after-free on the GPU; never freeing leaks. Kotlin's GC does not manage
native GPU memory.

**Decision.**

- Resources have an explicit `destroy()`.
- Each successful backend submission returns a unique token. The core records which resources each
  submission references.
- Destruction is deferred until every submission that references the resource reports completion
  through `RenderBackendDevice.isSubmissionComplete(token)`.
- A resource with no in-flight references is destroyed immediately.
- Use-after-destroy is a validation error, not undefined behavior. Translation at submit rejects
  resources already marked destroyed, so new work cannot extend a resource's lifetime.
- `waitIdle()` and `close()` drain outstanding submissions and release deferred resources.
- The backend owns the completion signal and must never report completion early.

**Consequences.**

- Deferred-free latency follows actual queue depth rather than a guessed frame count.
- A backend must provide truthful, monotonic completion queries and unique pending submission
  tokens.
- Memory peaks depend on how many submissions the application keeps in flight.
- Tests use the null backend's manual completion mode to verify that resources survive until the
  exact submission completes.
- Frame pacing and per-frame rings can be layered on top, but they are not the lifetime mechanism.

**Alternatives rejected.**

- *Rely on finalizers/GC.* Rejected: nondeterministic and does not understand GPU submission.
- *Free immediately after encoding.* Rejected: unsafe with multiple frames in flight.
- *A raw frames-in-flight counter as the source of truth.* Rejected: it approximates GPU completion,
  breaks when submissions outlive or complete earlier than the counter assumes, and cannot represent
  several independent submissions. A frame counter may later drive pacing or resource rings, but not
  correctness.

---

## ADR-0016: Runtime backend selection with a mandatory null backend

**Status:** Accepted (registry and null backend implemented; real backends planned)

**Context.** Compile-time backend selection (bgfx) prevents a single binary from choosing at
runtime. A null backend is essential for tests and headless tools.

**Decision.** Backends are registered at runtime through an instance-scoped registry. Device creation
accepts a preference order, selects the first available registered backend, and falls back to any
other available registered backend. The default preference ends with the null backend. The null
backend implements the full HAL, validates handles and descriptors, and records commands without
executing them; it is not registered implicitly, so tests and tools opt in.

**Consequences.**

- CI can run rendering-core tests without a GPU.
- The same binary can choose Vulkan, Metal, WebGL2, or WebGPU based on the platform once those
  backends exist.
- Backend registration does not use global mutable state; every application or test owns its
  registry.

**Alternatives rejected.**

- *Compile-time-only selection.* Rejected: poor for distributable applications and tests.
- *Skip the null backend.* Rejected: every rendering test would require a GPU and platform window.

---

## ADR-0017: No support claims without validation

**Status:** Accepted (policy)

**Context.** Engine projects often list platforms and backends that were never built or run,
misleading users. The user explicitly forbade claiming unvalidated support.

**Decision.** A platform or backend is listed as **validated** only when:

1. it compiles in CI on the target platform;
2. its tests run on that platform;
3. a smoke test exercises the feature end to end (for a backend, drawing a frame).

Everything else is marked **planned**, **compiles only**, or **untested**.

**Consequences.**

- Documentation and release notes carry a status table.
- iOS and Metal remain untested until macOS CI exists.
- `mingwX64` is compile-validated but not runtime-validated.
- Marketing claims lag implementation, which is intentional.

**Alternatives rejected.**

- *List intended platforms as supported.* Rejected: dishonest and harmful to users.

---

## ADR-0018: Asset identity separated from location

**Status:** Proposed (Phase 3)

**Context.** Godot's `ResourceUID`, Bevy's `AssetId`, and O3DE's `AssetId` all separate stable
identity from file paths. Paths change; identities should not.

**Decision.** Introduce a 64-bit `AssetId` generated at import time and stored in a catalog that
maps ID → source location and type. Runtime handles reference `AssetId`, not paths. Asset state
changes are published as events.

**Consequences.**

- Moving or renaming source assets does not break references.
- The catalog becomes a required build artifact for cooked games.
- Import must be deterministic to keep IDs stable across machines.

**Alternatives rejected.**

- *Path-based references.* Rejected: renames break content and diffs become noisy.

---

## ADR-0019: Coroutines for asynchronous work, not for per-frame hot paths

**Status:** Proposed

**Context.** KorGE uses coroutines for scene lifecycle and async work. Coroutines are excellent for
asset loading, streaming, and editor operations, but per-frame allocation and suspension in hot
paths is costly.

**Decision.** Use `kotlinx.coroutines` for asset I/O, streaming, and long-running tool operations.
Frame update and render extraction remain synchronous and allocation-conscious. Async results are
delivered to the main thread through a queue drained at a defined point in the frame.

**Consequences.**

- The engine owns a structured concurrency scope tied to lifecycle.
- Cancellation must be honored on shutdown.
- No coroutine suspension inside render command recording.

**Alternatives rejected.**

- *Coroutine-driven frame loop.* Rejected: harder to reason about frame ordering and allocation.
- *Callback-only async.* Rejected: cancellation and error propagation are worse.

---

## ADR-0020: Apache-2.0 license

**Status:** Accepted

**Context.** Ether is a reusable engine/library. The Kotlin ecosystem commonly uses Apache-2.0,
which includes an explicit patent grant.

**Decision.** License the project under Apache License 2.0.

**Consequences.**

- Contributors grant a patent license.
- Distributions must preserve notices.
- The license is compatible with the Kotlin standard library and common dependencies.

**Alternatives rejected.**

- *MIT.* Simpler, but lacks an explicit patent grant.
- *GPL.* Rejected: incompatible with the goal of broad engine adoption.

# Changelog

All notable changes to Ether are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

### Added

#### `ether-map` (map engine vertical) and `ether-map-data` (ADR-0021)

- The map engine of the removed Urban `engine-standalone/` project ported as a KMP vertical
  (JVM, linuxX64, wasmJs), `explicitApi: strict`, zero compiler warnings: OSM streaming XML reader
  (String/UTF-8 bytes, numeric/named entities, `.gz` rejected with an explicit message) and the
  feature mapper; local ENU projection with WebMercator XYZ tiles, spatial index and validation;
  ear-clipping triangulation with hole bridging; building/road/box mesh builders with a central
  style; scene assembly from OSM stores and cartographic packages, scene graph with cycle checks,
  Möller–Trumbore raycast and screen projection, deterministic simulated fleet; orbit camera with
  the user-configurable `MapModePolicy` (220 m / 420 m swap thresholds with hysteresis, one mode
  per frame, startup Overhead2D), Vulkan-convention projections (Y-flip, depth 0..1) and frustum
  extraction; tile streaming with LRU byte budget, distance prioritization and package-backed
  loader; frustum-culling render queue with batching/instancing/layers and a recorder-backed frame
  renderer; `MapRuntime` and the transactional `MapEngine`.
- Divergences from the removed engine documented in code: unified ENU type, shared `GeoPoint`/
  `GeoBounds`, consistent streaming distance metric (both sides projected into local meters),
  `loadNext` drains recorded failures, deterministic Kotlin RNG for the fleet, decorative queue
  parameters (`CullMode`, `InstanceBatch`) dropped.
- `ether-map-data` carries the shared `urban-map-package` v1 contract (manifest, `TileReference`,
  `FeatureRecord`, `MapPackageLoader.decodeFeatures`, validation) used by the vertical, the web
  runtime and the compiler.
- Measured suite: 306 tests on JVM, 306 on linuxX64, 303 on wasmJs (89 unique in `ether-map`),
  0 failures; headless frame path validated with `ether-render-null` (recorder submissions,
  `waitIdle`, `liveResourceCount == 0` after close).

#### `ether-render-lwjgl` (JVM) (ADR-0022)

- JVM Vulkan backend through LWJGL 3.4.3: `LwjglVulkanBackend` probes a Vulkan 1.3
  dynamic-rendering device (cached probe, remembered failures), `LwjglBackendDevice` implements the
  backend HAL (buffers, textures, samplers, SPIR-V shader modules, render pipelines, command
  buffers), and the Linux/x64 `liblwjgl.so` ships inside the artifact (`natives/linux/`), extracted
  at runtime by `NativeBootstrap`.

### Changed

- `MapSceneRenderer.close()` now also destroys the two shader modules it created (previously
  leaked against the device registry).

- Real Vulkan backend implemented on `linuxX64`: opens the loader (`libvulkan.so.1`) via `dlopen`
  with no build-time Vulkan link, probes a device (`VulkanProbe`), and implements the backend HAL
  (`VulkanBackendDevice`) — buffers (`Uniform`/`Storage` staged through a completed upload, others
  written through a persistent host mapping), device-local textures resting in
  `VK_IMAGE_LAYOUT_GENERAL`, render pipelines with dynamic rendering, and single-fence submissions
  under the single threaded owner model.
- Loader bindings honor the Vulkan two-phase entry point model: instance-level functions resolve
  only through an existing instance (`VulkanInstanceApi`), while the three pre-instance functions
  live in `VulkanGlobalApi`.
- `writeBuffer` HAL method plus a backend-local `readTextureBytes` staging readback used by tests
  and diagnostics.
- Three smoke tests execute real GPU submission on the verified device (AMD RADV PHOENIX, Mesa):
  a built-in-shader triangle rendered to a 64×64 `Rgba8Unorm` texture asserting exact pixel values
  (512 covered pixels, precise row extents, cleared corners), a portable `GraphicsDevice`
  end-to-end integration test, and a readback token rejection test. Measured test runs: JVM 197
  passed; Linux x64 200 passed (197 portable + 3 Vulkan); 0 failures.

## 0.1.0 — 2026-11-07

### Added

#### Build and project structure

- Multi-module JetBrains Amper project with a root `project.yaml`.
- Target matrix: JVM, Android, Linux x64, Windows x64 (`mingwX64`), Wasm (`wasmJs`), iOS arm64, and
  iOS simulator arm64.
- `explicitApi: strict` and publishing metadata for every module.
- Modules: `ether-math`, `ether-core`, `ether-testing`, `ether-render-api`, `ether-render-core`,
  `ether-render-null`.

#### `ether-math`

- `Vec2`, `Vec3`, `Vec4` with arithmetic, dot/cross, length, normalization, lerp, min/max,
  axis-angle rotation, and approximate equality.
- `Quat` with axis-angle and Euler constructors, composition, conjugate, normalization, slerp, and
  vector rotation.
- `Mat3` and `Mat4` stored column-major, with translation/scale/rotation constructors,
  multiplication, transformation of points/directions, transpose, determinant, inverse, normal
  matrix, perspective, orthographic, and look-at projections.
- `Transform` with local/world composition helpers.
- `Aabb` and `BoundingSphere` with construction, containment, intersection, union, and transform.
- `MathUtils` with scalar helpers and angle conversion.

#### `ether-core`

- `Engine` lifecycle state machine: Created, Initializing, Running, Paused, Stopping, Stopped,
  Failed, with validated transitions and subclass hooks.
- `EngineConfig` with application name, log level, frame-rate cap, maximum delta, and validation
  toggle.
- `FrameTimer`, `FrameTiming`, `TimeSource`, and an anchored `MonotonicTimeSource`.
- Synchronous, type-keyed `EventBus` with cancellable subscriptions and re-entrant publishing.
- Logging facade: `Logger`, `SinkLogger`, `LogSink`, `LogRecord`, `LogLevel`, `NoOpLogSink`, and
  platform console sinks.
- Error hierarchy: `EtherException`, `EngineInitializationException`,
  `InvalidEngineStateException`, `MissingServiceException`.

#### `ether-testing`

- `FakeTimeSource` with deterministic advancement.
- `RecordingLogSink` with level and message queries.
- Tolerance-based float assertions that do not depend on `kotlin.test`.
- Direct tests for the test utilities.

#### `ether-render-api`

- `BackendKind`, `GraphicsFeature`, `GraphicsLimits` (`Conservative` and synthetic `Permissive`
  presets), and `GraphicsCapabilities` with supported texture formats and layout conventions.
- Typed value-class handles for buffers, textures, samplers, shader modules, render pipelines, and
  command buffers.
- Validated descriptors and enums for buffers, 1D/2D/3D textures (array layers, mip levels,
  multisampling, formats, usage), samplers, shader modules, vertex layouts, render pipelines,
  color/depth attachments, viewport, scissor, and copy commands.
- Backend HAL: `RenderBackend`, `RenderBackendDevice`, backend-token `BackendCommand` lists,
  translated render-pass descriptors, submission tokens with completion queries, and close.
- Descriptor tests covering mip bounds (2D array layers do not inflate the mip extent), format and
  usage pairing, vertex-layout overlap, pipeline targets, sampler ranges, and pass attachment
  uniqueness.

#### `ether-render-core`

- `GraphicsDevice` facade for resource creation/destruction, command encoders, submission,
  completion polling, draining, and close.
- Generational `HandleTable` and typed `ResourceRegistry`; stale, destroyed, and cross-device
  handles are rejected.
- Capability and numeric-limit validation before any backend call: buffer sizes and usage, texture
  formats/dimensions/samples, samplers, shader stages, vertex layouts, color/depth targets, and
  multisample state.
- `CommandEncoder` with eager validation of pass pairing, attachment usage/formats/dimensions and
  sample counts, pipeline compatibility, vertex/index binding ranges, viewport/scissor bounds, draw
  budget, instancing support, copy pitch/range/mip/usage, and finish state.
- `CommandTranslator` resolves portable handles to backend tokens at submission; destroyed
  resources are rejected rather than translated.
- Submission-token deferred free: resources stay alive until every referencing submission reports
  completion; `waitIdle` and `close` drain all outstanding work.
- Linear `RenderGraph` with imported/produced read validation, feedback-loop rejection, unique pass
  names, in-order recording, and command-buffer encoding.
- `RenderBackendRegistry` with `Vulkan → Metal → WebGl2 → WebGpu → Null` preference, fallback to any
  available backend, and device creation.
- Render error hierarchy: `RenderException`, `RenderValidationException`,
  `InvalidRenderResourceException`, `RenderDeviceClosedException`,
  `RenderBackendUnavailableException`, `RenderSubmissionException`, and `RenderGraphException`.

#### `ether-render-null`

- `NullBackend` with always-available synthetic permissive capabilities.
- `NullBackendDevice` implementing the full HAL with token/resource tracking, recorded command
  buffers, retained submission batches, automatic or manual completion, and explicit close.
- Tests for resource lifetime, shader-stage validation, submission completion modes, unknown
  tokens, and close behavior.

#### Test totals

- 197 tests across six modules: 42 math, 41 core, 9 test utilities, 20 render API, 77 render core,
  and 8 null backend.
- All 197 pass on JVM, Linux x64, and headless Wasm. Android and `mingwX64` compile but are not
  runtime-tested.

#### Documentation

- Source-level engine research covering Godot, Bevy, O3DE, Filament, bgfx, wgpu, KorGE, and Kotlin
  Multiplatform graphics constraints.
- Architecture decision records for the build system, target matrix, module policy, math
  conventions, lifecycle, events, logging, time, scene/ECS, handles, rendering, assets, and
  licensing.
- Engine architecture, rendering architecture, multiplatform strategy, building, and testing
  documents.
- README, CONTRIBUTING, LICENSE, and this changelog.

### Fixed

- Corrected `Mat4.inverse()` and `Mat3.inverse()` to use adjugate cofactors with the correct
  row/column transposition; added round-trip and singular-matrix tests.
- Replaced per-call monotonic re-anchoring with a single anchored mark; the previous implementation
  reported near-zero deltas.
- Replaced the Wasm `expect object` console sink with an `expect fun` hook, removing the
  `expect`/`actual` class Beta warning and making the interop `js(...)` calls single-expression
  top-level functions.
- Removed JVM-only `System.currentTimeMillis` from common logging; wall-clock timestamps now use
  `kotlin.time.Clock`.
- Fixed a frame-timing test that confused one second with a 60 Hz frame.
- Fixed texture mip-level validation so 2D array layers are not counted as mip extents.
- Fixed a Kotlin/Native `ConcurrentModificationException` in `pollCompletedSubmissions` by reading
  each pending submission before removing it from the iterator.
- Backend command-buffer creation or submission failures now destroy partially created command
  buffers and release in-flight resource references.

### Known limitations

- No real GPU backend. The null backend validates and records commands but performs no rasterization
  and produces no visual output.
- No window, surface, swap-chain, or presentation layer.
- The render graph is linear: passes execute in declaration order. Pass culling, topological
  reordering, barrier derivation, transient resource pooling, and aliasing are not implemented.
- No bind-group/descriptor-set API, compute pass, indirect draw recording, or texture-to-buffer copy
  yet.
- Deferred free is keyed to backend submission completion; there is no frame pacing or
  frames-in-flight orchestration in the engine yet.
- No scene graph, ECS, asset pipeline, input, audio, or physics.
- Frame-rate capping is configured but not enforced; applications must pace their own loops.
- iOS targets are declared but cannot be built on the Linux host.
- `mingwX64` compiles but is not runtime-tested.
- Android compiles but is not runtime-tested.
- Real backend support will be claimed only after a CI smoke test draws a frame on the target.

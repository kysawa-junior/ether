# Engine Research

This document records the source-level research that informs Ether's architecture. It is not a
feature survey. Every claim below is tied to a file that was read during the study; when a corpus
did not contain the expected material, that gap is stated explicitly.

## Method and corpus

Reference implementations were fetched from their public repositories on 2026-11-07 (default
branches, not release tags), and read locally. The corpus is stored in:

```
~/.local/share/opencode/ether-research/
```

Files are cited by their corpus name plus line number, for example `godot_node.h:54`. Line numbers
refer to the fetched snapshots and will drift as upstream changes. The corpus contains:

| Engine | Files | Focus |
|---|---:|---|
| Godot | 23 | scene tree, resources, UID system, RenderingServer/RenderingDevice, materials, importing |
| Bevy | 16 | ECS storage, entity allocation, schedules, executor, plugins, assets, renderer init |
| O3DE | 8 | component lifecycle, transform component, asset manager, RHI system, RPI passes |
| Filament | 21 | engine/view/renderer, materials, command stream, drivers, post-processing, glTF |
| bgfx | 5 | public C API, handles, capabilities, renderer internals, configuration |
| wgpu | 18 | public API, resource lifetime, validation, HAL, Vulkan/Metal backends |
| KorGE | 9 | Kotlin Multiplatform layout, entry point, scene container, render context, window |
| KMP support | 5 | kotlin-wrappers, Vulkan-Hpp, Dawn, Emscripten, kotlinx.coroutines |

**Limitations.** This is a targeted corpus, not a full source tree. Godot's renderer backend
directories, Bevy's render-graph crates, bgfx's per-backend renderer implementations, and O3DE's RHI
device layer beyond `RHISystem` were not fetched. Statements about those areas are limited to what
the fetched files show, and gaps are called out. No benchmark was run; performance statements are
about design intent visible in code, not measured results.

---

# Godot

## Source files inspected

| File | What it reveals |
|---|---|
| `godot_node.h` | `Node` hierarchy, children, groups, process flags |
| `godot_scene_tree.h` | `SceneTree` as `MainLoop`, process groups, group iteration |
| `godot_resource.h` | `Resource : RefCounted`, path cache, RID bridge |
| `godot_resource_uid.h` | 64-bit resource UID mapping |
| `godot_rid.h` | 64-bit opaque RID |
| `godot_rendering_server.h` | Abstract server API addressed by RID |
| `godot_rendering_device.h` | Low-level explicit GPU API (textures, shaders, pipelines, draw lists) |
| `godot_rs_default.h` | Concrete server implementation, frame draw entry point |
| `godot_scene_render_rd.h` | Scene renderer composed from effect subsystems |
| `godot_material.h` | `Material` as `Resource`, `ShaderMaterial` parameter cache |
| `godot_shader.h` | `Shader` modes (spatial, canvas item, particles, sky, fog) |
| `godot_mesh.h` | Mesh surfaces and per-surface material |
| `godot_importer_scene.h` | Editor import pipeline for scenes |
| `godot_local_vector.h` | Contiguous, trivially-destructible container |
| `godot_vector.h` | Copy-on-write vector |

## Architecture

### Scene tree and Node model

`Node` is an `Object` subclass (`godot_node.h:54`). Hierarchy is stored as a parent pointer plus a
child cache (`godot_node.h:205`, `godot_node.h:208`), with mutation through `add_child`
(`godot_node.h:525`) and `remove_child` (`godot_node.h:527`). Nodes are also addressable by group
membership (`godot_node.h:573`), which is a flat index orthogonal to the hierarchy. Processing is
opt-in per node through `set_process` and `set_physics_process`
(`godot_node.h:656`, `godot_node.h:660`).

`SceneTree` is a `MainLoop` (`godot_scene_tree.h:92`) that owns process groups
(`godot_scene_tree.h:120`) and can process groups across threads
(`godot_scene_tree.h:244`). It exposes node counts and group queries
(`godot_scene_tree.h:415`, `godot_scene_tree.h:419`). This is a classic hierarchical scene graph:
transforms and lifetimes follow parent-child links, while groups provide a second, flat access path.

### Resource management and lifetime

`Resource : RefCounted` (`godot_resource.h:52`) is the asset base class. Resources can carry a path
(`godot_resource.h:144`) and can expose an RID for server-side objects
(`godot_resource.h:119`, `godot_resource.h:184`). `ResourceCache` is a friend of both `Resource` and
`ResourceLoader` (`godot_resource.h:197`), which centralizes path resolution and lifetime.

Identity is separated from location through `ResourceUID`: IDs are `int64_t`
(`godot_resource_uid.h:44`), stored in a bidirectional map (`godot_resource_uid.h:56`) and created
with `create_id` (`godot_resource_uid.h:77`). This lets scenes reference assets by stable identity
while files move.

### RenderingServer and RenderingDevice

Godot explicitly separates the scene-facing server from the GPU-facing device:

- `RenderingServer` is an abstract singleton (`godot_rendering_server.h:66`) whose API is almost
  entirely `RID`-addressed: `texture_2d_create` (`godot_rendering_server.h:112`),
  `mesh_create_from_surfaces` (`godot_rendering_server.h:199`), `multimesh_create`
  (`godot_rendering_server.h:259`). `RenderingServerDefault` implements it and owns the frame draw
  entry (`godot_rs_default.h:47`, `godot_rs_default.h:91`).
- `RID` is a 64-bit opaque value (`godot_rid.h`), so the server never exposes raw GPU objects.
- `RenderingDevice` is a lower-level explicit API: `texture_create` (`godot_rendering_device.h:469`),
  `framebuffer_create` (`godot_rendering_device.h:716`), `vertex_buffer_create`
  (`godot_rendering_device.h:874`), `shader_create_from_spirv` (`godot_rendering_device.h:1042`),
  `uniform_set_create` (`godot_rendering_device.h:1199`), `render_pipeline_create`
  (`godot_rendering_device.h:1283`), and draw-list commands such as
  `draw_list_bind_render_pipeline` (`godot_rendering_device.h:1562`), `draw_list_draw`
  (`godot_rendering_device.h:1570`), and `draw_list_end` (`godot_rendering_device.h:1580`). It is
  initialized explicitly (`godot_rendering_device.h:1913`).

This two-level split keeps the high-level scene API stable while allowing a lower-level backend to
change. It also means there are two resource namespaces to keep consistent (scene RIDs and device
RIDs), a cost Ether should avoid by using one generational handle type per resource domain.

### 2D vs 3D

The server exposes 2D canvas items and 3D meshes/instances in the same abstract interface (for
example `mesh_create_from_surfaces` and `multimesh_create` alongside canvas-item calls in
`godot_rendering_server.h`). The renderer composes 2D/3D effects in one scene-render implementation:
`godot_scene_render_rd.h:33-50` includes bokeh depth-of-field, copy effects, FSR, luminance,
resolve, SMAA, tone mapping, variable-rate shading, GI, sky, and light storage. The separation is
therefore architectural (canvas vs spatial shader modes) rather than two independent engines.

### Materials and shaders

`Material : Resource` wraps an RID (`godot_material.h:38`, `godot_material.h:43`). `ShaderMaterial`
holds a `Ref<Shader>` and caches shader parameters (`godot_material.h:95`, `godot_material.h:97`,
`godot_material.h:126`). `Shader` is also a `Resource` with modes for spatial, canvas-item,
particles, sky, fog, and texture-blit rendering (`godot_shader.h:39`, `godot_shader.h:46`), and its
source is set as text (`godot_shader.h:89`). Meshes expose per-surface materials
(`godot_mesh.h:81`).

### Asset importing

Scene import is an editor-facing extension point: `EditorSceneFormatImporter` is a `RefCounted`
(`godot_importer_scene.h:49`) with a virtual `_import_scene` (`godot_importer_scene.h:61`) and
configurable import options. Runtime code consumes imported resources; it does not parse source
formats directly.

## Strengths

- Clear separation between scene graph, server API, and device API.
- Stable asset identity via 64-bit UIDs, independent of paths.
- Resource lifetime handled by reference counting, with a central cache.
- Renderer effects are modular (`godot_scene_render_rd.h:33-50`).
- Groups provide a cheap flat query path alongside hierarchy.

## Weaknesses and trade-offs

- Two RID namespaces (server RIDs and device RIDs) complicate ownership and debugging.
- Reference counting on resources makes cycles possible and requires care at teardown.
- `RenderingServer` is a very large abstract interface, which is costly to implement and mock.
- Node processing flags and group membership are mutable state spread across the node, making
  scheduling behavior harder to reason about than an explicit system schedule.

## Lessons for Ether

Adopt:

- Opaque, value-type handles for GPU resources; never expose backend objects.
- Stable asset IDs plus a separate path/catalog mapping.
- Modular render effects rather than one monolithic renderer.
- A flat query path (groups/tags) in addition to hierarchy.

Avoid:

- Duplicating a server RID namespace and a device RID namespace; use one handle domain with typed
  resource kinds and generations.
- A single enormous server interface; split by resource domain and keep interfaces small.

---

# Bevy

## Source files inspected

| File | What it reveals |
|---|---|
| `bevy_entity.rs` | Entity identity, index and generation, allocation lifecycle |
| `bevy_world.rs` | World storage, spawn/despawn, component and resource access, queries |
| `bevy_archetype.rs` | Archetype model and table-vs-sparse-set storage split |
| `bevy_sparse_set.rs` | Sparse array/set implementation |
| `bevy_query.rs` | Typed query iteration and filters |
| `bevy_schedule.rs` | Schedule module layout and ordering tests |
| `bevy_schedule_config.rs` | Sets, before/after, run conditions, ambiguity |
| `bevy_executor.rs` | Multi-threaded executor and conflict tracking |
| `bevy_app.rs` | App, plugins, update/run |
| `bevy_main_schedule.rs` | Standard schedule stages, fixed update |
| `bevy_assets.rs` | Asset storage, handles, events |
| `bevy_renderer.rs` | Renderer initialization, device/queue resources, render graph marker |

The concrete `Schedule` struct lives in a submodule that was not fetched
(`bevy_schedule.rs:1-13` shows `mod schedule;` and re-exports). Claims about schedule internals are
therefore based on the configuration API and executor, not on the struct itself.

## Architecture

### Entity identity

Entities use an index plus a generation: `EntityIndex` wraps a `NonMaxU32`
(`bevy_entity.rs:151`) and `EntityGeneration` is a separate `u32` (`bevy_entity.rs:264`). The module
documentation spells out the lifecycle: unallocated, allocated, spawned, despawned, freed; freeing
bumps the generation so old references become invalid (`bevy_entity.rs:40-92`). This is the standard
solution to the ABA problem and the model Ether should use.

### Storage: archetypes and sparse sets

Bevy's archetype documentation is unusually explicit: an archetype is the unique set of components
on a group of entities; table components are stored together in one table, while sparse-set
components may be shared across archetypes that differ only by sparse-set components
(`bevy_archetype.rs:1-15`). Archetypes are created but never cleaned up; empty archetypes remain
(`bevy_archetype.rs:14`). `SparseArray`/`SparseSet` provide the sparse storage
(`bevy_sparse_set.rs:68-121`).

`World` owns entities, the entity allocator, component metadata, resources, archetypes, storages,
bundles, observers, and change ticks (`bevy_world.rs:96-110`). It exposes `spawn`
(`bevy_world.rs:1243`), `despawn` (`bevy_world.rs:1574`), `get`/`get_mut`
(`bevy_world.rs:1342`, `bevy_world.rs:1363`), `query` (`bevy_world.rs:1862`), and resources
(`bevy_world.rs:2056`, `bevy_world.rs:2280`).

### Systems and schedules

Ordering is declarative: `ScheduleConfig<T>` (`bevy_schedule_config.rs:89-91`) supports
`in_set` (`:111`), `before` (`:128`), `after` (`:144`), weak variants, and `distributive_run_if`
(`:224`). Sets are interned (`bevy_schedule_config.rs:8`) so the graph uses compact identifiers.
Ambiguity is a first-class concept (`bevy_schedule_config.rs:25-31`). The multi-threaded executor
tracks per-system component access and conflicting systems before running
(`bevy_executor.rs:85-86`, `:149`), and uses a thread executor to run non-conflicting systems
(`bevy_executor.rs:267-270`).

The standard schedule stages are explicit: `First`, `PreUpdate`, `Update`, `PostUpdate`, `Last`,
plus fixed-update stages and a render schedule (`bevy_main_schedule.rs:81-163`). `App` composes
plugins (`bevy_app.rs:85`, `:125`), and `run` swaps in a runner and consumes the app
(`bevy_app.rs:185-200`).

### Assets

`Assets<A>` stores asset values by `AssetId` (`bevy_assets.rs:276-291`). Index IDs live in dense
storage; UUID IDs live in a hash map (`bevy_assets.rs:278-281`). Insertion and removal are explicit
(`bevy_assets.rs:143`, `:170`), and change events are queued. This is a clean separation of asset
identity, storage, and change notification.

### Renderer

The renderer module exposes `RenderGraph` (`bevy_renderer.rs:48`), wraps wgpu objects as
`RenderDevice`, `RenderQueue`, `RenderAdapter`, and `RenderInstance`
(`bevy_renderer.rs:143-156`), and initializes them asynchronously
(`bevy_renderer.rs:197`). The render-graph implementation itself was not in the corpus.

## Strengths

- Generational entity IDs solve dangling-reference bugs cheaply.
- Archetype + sparse-set split lets hot and cold components use different layouts.
- Explicit schedule stages and declarative ordering make frame behavior inspectable.
- Parallel execution is derived from declared access, not manual thread management.
- Asset IDs and asset events decouple storage from consumers.

## Weaknesses and trade-offs

- Archetypes are never reclaimed, so pathological component churn grows metadata.
- Parallel ECS requires `Send`/`Sync` discipline and complex unsafe code; Kotlin/Native and Wasm
  have weaker threading guarantees, so Ether cannot copy the executor wholesale.
- Schedule configuration is powerful but easy to over-constrain; ordering can become implicit.
- The ECS is the whole application model, which makes simple hierarchical transforms awkward unless
  a separate hierarchy layer is added.

## Lessons for Ether

Adopt:

- Index+generation entity IDs.
- Hot components in sparse sets; rarely accessed components in archetype/table storage.
- Declarative ordered stages with explicit ambiguity diagnostics.
- Asset IDs plus change events.

Adapt, do not copy:

- Single-threaded scheduling first. Kotlin/Wasm has no shared-memory threads by default, and
  Kotlin/Native concurrency is stricter than Rust's. Parallel execution can come later behind a
  capability flag.
- Keep a hierarchical scene for transforms; do not force parent-child traversal into ECS queries.

---

# Open 3D Engine (O3DE)

## Source files inspected

| File | What it reveals |
|---|---|
| `o3d_component.h` | `Component` lifecycle: `Init`, `Activate`, `Deactivate` |
| `o3d_transformcomponent.h` | Transform component, local/world matrices, parent cache |
| `o3d_assetmanager.h` | Asset IDs, handlers, catalogs, load behavior |
| `o3d_assetcatalog.h` | Catalog interface for asset resolution |
| `o3d_rhisystem.cpp` | RHI device enumeration, initialization, frame scheduler |
| `o3d_shadowmappass.h` | Raster pass specialization for shadows |
| `o3d_lightcullingpass.h` | Light culling pass |
| `o3d_deferredmesh.h` | Deferred draw-packet lifetime and material invalidation |
| `o3d_rhi_device.h` | Not present in the fetched corpus (fetch failed) |

O3DE coverage is the thinnest in this study. Conclusions are limited to the component and pass
layers.

## Architecture

### Component lifecycle

O3DE separates initialization from activation. `Init` initializes resources but the component is
not active until the system calls `Activate` (`o3d_component.h:64`, `:169-173`); `Activate` and
`Deactivate` are pure virtual (`o3d_component.h:182`, `:196`), and the system always calls
`Deactivate` before destroying a component (`o3d_component.h:70`, `:191`). This makes the
"constructed but not running" state explicit, which is valuable for hot reload and editor modes.

### Transform

`TransformComponent` implements a transform interface (`o3d_transformcomponent.h:42`), exposes
local and world matrices (`:58`, `:60`), and caches a pointer to the parent transform to avoid
repeated hierarchy calls (`:193`). The cache is explicitly documented as valid only while the parent
exists (`:193`). This is the classic hierarchy optimization: compute world transforms top-down and
cache, but invalidate carefully.

### Assets

`AssetManager` resolves `AssetId` through registered handlers and catalogs
(`o3d_assetmanager.h:153-155`), with `GetAsset` (`:214`), `FindOrCreateAsset` (`:238`), and
`CreateAsset` (`:256`). Load status is reported through `AssetStatusUpdate` (`:66`), and asset
references carry load behavior. This separates asset identity, type-specific handlers, and
catalogs that map IDs to sources.

### Render passes and RHI lifecycle

Rendering is organized as RPI passes. `ShadowmapPass` is a `RPI::RasterPass`
(`o3d_shadowmappass.h:20-26`) that submits draw items through a frame-graph execute context
(`:81`) and owns a clear draw packet (`:66`). Deferred drawing caches `DeferredDrawPacket` per draw
list tag and tracks material changes (`o3d_deferredmesh.h:38-48`).

`RHISystem::Init` initializes devices, pipeline state cache, per-device limits, and the frame
scheduler (`o3d_rhisystem.cpp:48-114`). Physical devices are enumerated through the RHI factory
(`:121`), and a preferred adapter can be selected (`:146-153`). The device interface itself
(`o3d_rhi_device.h`) was not available, so the exact RHI Device contract is not analyzed here.

## Strengths

- Explicit two-phase component lifecycle (`Init` vs `Activate`).
- Pass-based rendering with frame-graph execution contexts.
- Asset identity resolved through pluggable handlers and catalogs.
- Physical adapter selection is explicit and configurable.

## Weaknesses and trade-offs

- The component system is heavy: descriptors, type IDs, and factories add boilerplate.
- Passes and draw packets have many special cases; the deferred path tracks material changes
  manually.
- The RHI is large and split across many files; the corpus gap makes it hard to judge its
  ergonomics.

## Lessons for Ether

Adopt:

- Separate `initialize` from `activate` for scene systems and components (useful for editor/pause).
- Per-draw-list caching with explicit invalidation when materials change.
- Adapter enumeration and explicit selection in the rendering device.

Avoid:

- Reflection-heavy component descriptors. Kotlin can use type-safe registration and interfaces
  without a descriptor system.

---

# Filament

## Source files inspected

| File | What it reveals |
|---|---|
| `filament_engine.h` | Engine ownership, backend type, resource tracking, render thread |
| `filament_view.h` | View contract: scene, camera, viewport, render target, post-processing |
| `filament_renderer.h` | Frame begin/end, frame-rate options, skip-frame |
| `filament_material.h` | Compiled material, instances, parameter API |
| `filament_materialbuilder.h` | Offline material compilation, domain/blending defaults |
| `filament_material.cpp` | Material instance and variant behavior |
| `filament_commandstream.h` | Type-erased command recording against a `Driver` |
| `filament_gl_driver.h` | OpenGL driver implementation |
| `filament_metal_driver.h` | Metal driver declaration |
| `filament_vulkan_driver.h` | Vulkan driver declaration |
| `filament_scene.cpp` | Light/renderable managers, shadow and light processing |
| `filament_postprocess.h/.cpp` | Bloom, TAA, dynamic resolution, tone mapping stages |
| `filament_gltfio.h` | glTF asset loading |
| `filament_gltfio_assetloader.cpp` | Asset loader implementation |

## Architecture

### Engine, Renderer, View

`Engine` owns all user-created objects and starts a render thread plus worker threads
(`filament_engine.h:170`). It exposes the backend type (`:195`, `:199`) and a lifecycle where the
user creates renderer, scene, view, and swap chain, then destroys them explicitly
(`filament_engine.h:129-149`).

`View` binds a scene, camera, viewport, and render target
(`filament_view.h:126`, `:153`, `:173`, `:196`), and carries post-processing switches such as bloom
options (`:490`) and `setPostProcessingEnabled` (`:727`). `Renderer` owns frame begin/end and
frame-rate policy (`filament_renderer.h:215-220`, `:304`).

### Material model

Materials are compiled offline into a binary blob (`filament_material.h:59`) and can be compiled
asynchronously at runtime for variants (`:228-271`). Each material has a default instance
(`:437`) and can create named instances (`:302`); parameters are set on instances, not on the
shared compiled material. `MaterialBuilder` builds packages (`filament_materialbuilder.h:695`) with
defaults of `BlendingMode::OPAQUE` (`:950`) and `MaterialDomain::SURFACE` (`:955`).

This two-level model (compiled material + parameterized instances) is the right shape for Ether:
compile shader permutations once, create lightweight instances per object.

### Backend abstraction

Filament records backend commands into a `CommandStream`; each command stores a pointer-to-member of
`Driver` and executes against a concrete driver (`filament_commandstream.h:71`, `:114-115`). The
OpenGL driver is `OpenGLDriver final : OpenGLDriverBase` (`filament_gl_driver.h:86`) and includes a
generated `DriverAPI.inc` (`:294`); Metal and Vulkan have corresponding driver headers. The driver
is therefore a wide, generated interface with one implementation per API, while command recording
is shared.

This validates a design Ether can use: a narrow, backend-agnostic command encoder that records
portable commands, and per-backend executors. It also shows the cost: the driver interface is wide
and generated, not hand-written.

### Scene, lighting, and post-processing

`FScene` uses `LightManager` and `RenderableManager` (`filament_scene.cpp:22-23`), processes
deferred updates, and caches shadow/light state (`:334`, `:360`, `:404-410`). Directional lights are
handled as a special case (`:501-522`). Post-processing is a set of passes: bloom
(`filament_postprocess.h:203-210`), TAA jitter (`:262`), and dynamic resolution (`:290`). Tone
mapping and other stages are part of the same post-process manager.

### glTF

`AssetLoader` consumes glTF/GLB bytes and produces `FilamentAsset` objects
(`filament_gltfio.h:178`), supports instanced assets (`:204`), and requires explicit destruction
(`:145`). A `MaterialProvider` supplies materials (`:68-70`). This is a clean importer boundary:
runtime assets are engine-native, and the glTF loader is an adapter.

## Strengths

- Compiled material plus lightweight instances.
- Backend command recording separated from driver execution.
- Explicit frame begin/end and frame-rate policy.
- Mature post-processing as composable passes.
- Clear glTF importer boundary.

## Weaknesses and trade-offs

- The driver interface is very wide, which raises backend implementation cost.
- Engine and view lifecycles are manual and easy to misuse; destruction order matters.
- Render thread plus worker threads adds synchronization complexity.
- Material variant compilation is asynchronous and can stall if not warmed.

## Lessons for Ether

Adopt:

- Material + material instance separation.
- Portable command recording with backend executors.
- Explicit frame begin/end and frame pacing.
- Composable post-process passes.
- Importer adapters that produce engine-native assets.

Avoid:

- A single generated mega-interface for all backends; split by resource domain.
- Unbounded async shader compilation without a fallback; provide a placeholder material.

---

# bgfx

## Source files inspected

| File | What it reveals |
|---|---|
| `bgfx_h` | Public C API: handles, caps, renderer types, state, submission |
| `bgfx_cpp` | Encoder submission, render frame, render thread, transient buffers |
| `bgfx_renderer.h` | Shared renderer utilities (not the backend interface) |
| `bgfx_config.h` | Compile-time backend and limit configuration |

The internal backend interface was not in the fetched `bgfx_renderer.h`; that file contains
utilities such as state caches and uniform caches (`bgfx_renderer.h:88-1044`), not the `Renderer`
contract. Backend-specific conclusions are therefore limited.

## Architecture

### Handles

Handles are 16-bit indices: `BGFX_HANDLE` defines a struct with `uint16_t idx` and an `isValid`
check against `kInvalidHandle` (`bgfx_h:22-24`). All resource kinds use this pattern
(`bgfx_h:528-539`). A 16-bit index is compact but caps the number of live resources at 65,535 and
provides no generation, so stale handles are only caught by the invalid sentinel and backend
bookkeeping.

### Capabilities

`bgfx::Caps` exposes GPU vendor/device IDs, a `Limits` struct, a 64-bit `supported` bitmask, and
layout conventions such as `homogeneousDepth` and `originBottomLeft` (`bgfx_h:600-680`). `Limits`
includes concrete fields such as `maxDrawCalls`, `maxTextureSize`, `maxTextureLayers`, `maxViews`,
`maxFrameBuffers`, `maxFBAttachments`, `maxPrograms`, `maxShaders`, `maxTextures`,
`maxTextureSamplers`, `maxComputeBindings`, `maxVertexStreams`, `maxVertexAttributes`,
`minUniformBufferSize`, and blit alignment (`bgfx_h:608-643`). This is a strong precedent for a
capability model that is more than a string list.

### Submission and state

Rendering is stateful and immediate: `setState` (`bgfx_h:1360`), `setTransform` (`bgfx_h:1470`),
`setIndexBuffer` (`bgfx_h:1540`), `setVertexBuffer` (`bgfx_h:1601`), then `submit` (`bgfx_h:1833`).
`touch` (`bgfx_h:1822`) keeps a view alive without drawing. Encoders accumulate submissions and
sort them by view and sort key (`bgfx_cpp:1567`, `:1638`). `renderFrame` drives one frame
(`bgfx_cpp:1915`), and the backend runs on a dedicated render thread initialized at
`bgfx_cpp:2556`.

### Backends and configuration

`RendererType` enumerates Noop, AGC, D3D11, D3D12, GNM, Metal, NVN, OpenGL ES, OpenGL, Vulkan, and
WebGPU (`bgfx_h:57-75`). Backends are selected at compile time by `BGFX_CONFIG_RENDERER_*` macros
(`bgfx_config.h:24-80`). This is a build-time backend matrix, not a runtime plugin system.

## Strengths

- Extremely small public handle surface.
- Capability struct with real limits, not just feature names.
- State/submit model is simple and portable.
- Dedicated render thread with batched submission.

## Weaknesses and trade-offs

- 16-bit handles without generation make stale-handle detection weak.
- Stateful global API is hostile to multiple devices and to multithreaded recording.
- Compile-time backend selection prevents shipping one binary that picks a backend.
- The public API hides lifetime rules; deferred destruction is implicit.

## Lessons for Ether

Adopt:

- Compact value handles and `isValid` semantics (but add generation counters).
- Capability limits with concrete numbers and layout conventions.
- Batched submission and a dedicated render thread where the platform allows it.
- A no-op backend for tests and headless tools.

Avoid:

- Global mutable state; scope state to a device or command encoder.
- 16-bit index-only handles; use `(index, generation)` and validate on use.
- Compile-time backend selection; choose the backend at runtime when possible.

---

# wgpu

## Source files inspected

| File | What it reveals |
|---|---|
| `wgpu_types.rs` | Shared public types (features, limits, surface config) |
| `wgpu_features.rs` | Feature bit constants |
| `wgpu_limits.rs` | Limit fields |
| `wgpu_instance.rs` | Instance, adapter enumeration, surface creation |
| `wgpu_adapter.rs` | Adapter, device request |
| `wgpu_device.rs` | Device resource creation, error scopes |
| `wgpu_surface.rs` | Surface API |
| `wgpu_command_encoder.rs` | Command encoding |
| `wgpu_render_pass.rs` | Render pass recording |
| `wgpu_core_resource.rs` | Resource tracking, destroy, drop staging |
| `wgpu_core_device_resource.rs` | Device resource internals |
| `wgpu_core_queue.rs` | Queue submission |
| `wgpu_core_pipeline.rs` | Pipeline and bind group layout modeling |
| `wgpu_core_validation.rs` | Validation entry points |
| `wgpu_hal.rs` | HAL `Api` trait and associated types |
| `wgpu_hal_vulkan.rs` | Vulkan HAL |
| `wgpu_hal_metal.rs` | Metal HAL |
| `wgpu_backend_core.rs` | Public-to-core backend bridge |

## Architecture

### Layer split

The repository is deliberately layered: `wgpu-types` holds shared data types, `wgpu` is the
idiomatic public API, `wgpu-core` implements validation and resource tracking, and `wgpu-hal` is the
per-API hardware abstraction. The HAL documentation confirms backends for Vulkan, Metal, Direct3D,
and GL (`wgpu_hal.rs:4`, `:80-83`), and the `Api` trait exposes associated `Instance`, `Surface`,
`Device`, `Queue`, and `SurfaceTexture` types (`wgpu_hal.rs:632-655`). This is the cleanest
layering in the corpus and the model Ether should follow.

### Instance → Adapter → Device → Queue

`Instance::enumerate_adapters` (`wgpu_instance.rs:149`), `request_adapter`
(`wgpu_instance.rs:171`), and `create_surface` (`wgpu_instance.rs:188`) form the entry path.
WebGL2 requires a `compatible_surface` when requesting an adapter (`wgpu_instance.rs:169-170`).
`Adapter::request_device` (`wgpu_adapter.rs:61`) takes a `DeviceDescriptor` with required features
and limits. Device creation of shader modules and pipeline layouts is explicit
(`wgpu_device.rs:154`, `:263`).

### Resources and lifetime

Resources carry tracking state and a destroyed flag. `ResourceType` is a trait
(`wgpu_core_resource.rs:200`); `Buffer::drop` and `Texture::drop` enqueue destruction
(`wgpu_core_resource.rs:576`, `:1802`), and destroyed resources are staged as
`DestroyedBuffer`/`DestroyedTexture` (`:1429`, `:2427`). `check_destroyed` guards use after destroy
(`:642`). This is the "explicit destroy + deferred actual free" pattern Ether uses; Ether keys
completion to backend submissions rather than to a frame counter.

### Errors

Errors are ordinary `Result` values from validation (`wgpu_core_validation.rs:777`, `:982`), plus
device-level error scopes: `push_error_scope` (`wgpu_device.rs:711`), `pop_error_scope`
(`:1125`), and `on_uncaptured_error` (`:675`). An `ErrorScopeGuard` pops on drop (`:1129`). This
gives both local recovery and a global fallback.

### Capabilities

Features are explicit bit constants such as `DEPTH_CLIP_CONTROL`,
`TEXTURE_COMPRESSION_BC`, `TIMESTAMP_QUERY`, `INDIRECT_FIRST_INSTANCE`, `SHADER_F16`,
`FLOAT32_FILTERABLE`, `DUAL_SOURCE_BLENDING`, and `CLIP_DISTANCES`
(`wgpu_features.rs:36-90`). Limits are concrete `u32`/`u64` fields including
`max_texture_array_layers`, `max_bind_groups`, `max_bindings_per_bind_group`,
`max_sampled_textures_per_shader_stage`, `max_uniform_buffer_binding_size`, `max_vertex_buffers`,
`max_buffer_size`, and `max_vertex_attributes` (`wgpu_limits.rs:162-245`).

## Strengths

- Clean four-layer split with a narrow HAL trait.
- Instance/adapter/device/queue lifecycle is explicit and portable.
- Real feature and limit model, queried at runtime.
- Explicit destroy plus deferred free integrates with frames-in-flight.
- Error scopes allow local recovery without aborting the process.

## Weaknesses and trade-offs

- The core is large and complex; validation bookkeeping is a significant fraction of the code.
- WebGL2 support constrains the API (compatible surface, feature subset).
- Async device request adds an async boundary to initialization.
- Resource tracking has a high implementation cost for a small engine.

## Lessons for Ether

Adopt:

- Layer split: portable types → public API → validation/core → backend HAL.
- Instance/adapter/device/queue terminology and flow.
- Runtime feature flags plus numeric limits.
- Explicit destroy with deferred free keyed to backend submission completion (see ADR-0015).
- Error scopes or a per-device error callback.

Adapt:

- Kotlin does not need a separate `wgpu-types` module at first; put portable types in
  `ether-render-api` and keep backends behind interfaces.
- Implement validation incrementally; start with handle validation and resource-state checks.

---

# KorGE

## Source files inspected

| File | What it reveals |
|---|---|
| `korge_korge.kt` | `Korge(...)` entry point, configuration, window loop |
| `korge_scenecontainer.kt` | Scene container, view lifecycle, coroutine scope |
| `korge_baseview.kt` | Base view abstraction |
| `korge_rendercontext.kt` | 2D render context, batching, flush |
| `korge_rendercontext2d.kt` | 2D-specific context extensions |
| `korge_gamewindow.kt` | Cross-platform window abstraction |
| `korge_build.gradle.kts` | KMP module configuration |
| `korge_core_build.gradle.kts` | Core module configuration |
| `korge_settings.gradle.kts` | Gradle settings and plugin management |

## Architecture

KorGE is the closest existing Kotlin Multiplatform game framework in the corpus. Its entry point is a
suspend function `Korge(...)` with a configuration data class, a `GameWindow`, a `Stage` builder,
and a `main` block (`korge_korge.kt:49`, `:116`, `:85-94`). It obtains a real game window from the
configuration or coroutine context (`:197`) and runs a window loop (`:201-205`).

Scenes are managed by `SceneContainer`, a `UIView` that is also a `CoroutineScope`
(`korge_scenecontainer.kt:55`), with child injection and lifecycle transitions. Rendering goes
through `RenderContext`, which owns the accelerated graphics object and batches draw calls until
`flush` (`korge_rendercontext.kt:21-43`, `:112`, `:163`). The window abstraction is an `expect`
factory (`korge_gamewindow.kt:56`) over a common `GameWindow` interface that hides platform event
loops and window properties (`:73-88`).

The Gradle files show a conventional KMP module layout with per-target source sets and a shared
core module. KorGE demonstrates that a Kotlin-first engine can target desktop, Android, iOS, JS, and
Wasm, but it also shows the cost: platform abstractions leak through `expect`/`actual`, and 2D
batching is deeply tied to its own render context.

## Strengths

- Proven multi-target Kotlin architecture.
- Coroutine-based scene lifecycle fits async asset and UI work.
- Batched 2D rendering with an explicit flush point.
- A single `GameWindow` abstraction across platforms.

## Weaknesses and trade-offs

- The API is 2D-first; adding a 3D renderer would require a parallel abstraction.
- `expect`/`actual` window and graphics surfaces spread platform details across source sets.
- Gradle-specific build logic is not reusable in Ether's Amper setup.

## Lessons for Ether

Adopt:

- Coroutines for asset loading and async lifecycle, not for per-frame hot paths.
- A `GameWindow`-style platform boundary.
- Explicit batching/flush semantics in the 2D path.

Avoid:

- Coupling the 3D device abstraction to the 2D render context.
- Letting platform window details leak into core modules.

---

# Kotlin Multiplatform graphics feasibility

## Source files inspected

| File | What it says |
|---|---|
| `kotlin_wrappers_readme.md` | Kotlin wrappers for JavaScript libraries, targeting JS and Wasm |
| `vulkan_hpp_readme.md` | Header-only C++ bindings for the Vulkan C API |
| `dawn_readme.md` | Native WebGPU implementation in C/C++ over D3D12, Metal, Vulkan, OpenGL |
| `emscripten_readme.md` | Compiles C/C++ to WebAssembly; provides Web support for OpenGL/SDL2 |
| `coroutines_readme.md` | Multiplatform coroutine library |

### Correcting a common assumption

`kotlin-wrappers` is **not** a C/C++ binding generator. Its README describes "Kotlin wrappers for
popular JavaScript libraries" and lists JS/Wasm targets (`kotlin_wrappers_readme.md:11-14`). It is
useful for browser APIs (including WebGL2/WebGPU JavaScript surfaces), but it does not generate
Vulkan or Metal bindings.

### Vulkan

`Vulkan-Hpp` is a header-only C++ binding over the Vulkan C API (`vulkan_hpp_readme.md:23-25`).
It cannot be consumed directly from Kotlin. The practical Kotlin/Native path is `cinterop` over the
Vulkan C headers, or a JNI bridge on Android. On Apple platforms, MoltenVK could expose Vulkan, but
Metal is the native path and avoids an extra translation layer.

### Metal

Metal is an Objective-C framework. Kotlin/Native Apple targets can interop with Objective-C
frameworks, so Metal is feasible on macOS/iOS. It cannot be built or tested on a Linux host, so
Ether must treat Metal as unvalidated until macOS CI exists.

### WebGL2

WebGL2 is a JavaScript API. From Kotlin/JS or Kotlin/Wasm it is reachable through JS interop. The
Kotlin/Wasm restriction encountered in Phase 1 is concrete: `js(...)` must be a single expression in
a top-level function body or property initializer, and browser globals such as `console` need
external declarations. This shapes the web backend: a thin JS interop layer with explicit external
declarations, not ad-hoc `js()` calls scattered through engine code.

### WebGPU and Dawn

Dawn is a native C/C++ implementation of WebGPU over D3D12, Metal, Vulkan, and OpenGL, and also
powers WebGPU in Chromium (`dawn_readme.md:15-25`). Embedding Dawn in a Kotlin/Wasm build is not
practical: the Wasm build runs in the browser, where the browser's own WebGPU implementation is
available. The sensible path is to call the browser WebGPU API through JS interop, not to ship Dawn
inside Wasm. Dawn could be relevant only for a native Kotlin/Native build that wants a WebGPU-shaped
API; that is out of scope for Phase 2.

### Emscripten

Emscripten compiles C/C++ to WebAssembly and provides Web support for OpenGL and SDL2
(`emscripten_readme.md:15-25`). It is relevant if Ether ever reuses a C/C++ renderer (bgfx, Dawn) in
a Wasm build. It is not needed for a pure Kotlin/Wasm backend that calls WebGL2/WebGPU directly.

### Backend feasibility matrix

| Backend | Targets | Integration path | Cost | Status in Ether |
|---|---|---|---|---|
| Vulkan 1.3 | linuxX64, mingwX64, Android | Kotlin/Native cinterop over C headers; NDK/JNI on Android | High | Planned, not implemented |
| Metal | macOS, iOS | Kotlin/Native Objective-C interop | High | Planned, untestable on Linux host |
| WebGL2 | wasmJs, js | JS interop with external declarations | Medium | Planned |
| WebGPU (browser) | wasmJs, js | JS interop; browser provides implementation | Medium | Optional, later |
| Null/test | all | Pure Kotlin | Low | Implemented in Phase 2 (`ether-render-null`) |

### Recommended backend order

1. **Null/test backend** — validates the device API, handle lifetimes, command encoding, and
   render-graph logic without claiming GPU support.
2. **Vulkan** on linuxX64 — the only real GPU backend testable on the current development host.
3. **WebGL2** — validates the web target and the shader translation path.
4. **Metal** — requires macOS CI; do not claim support before it runs.
5. **WebGPU** — optional, after WebGL2.

This order front-loads validation and avoids the common failure mode of writing three backends that
have never drawn a triangle.

---

# Cross-engine synthesis for Ether

## What Ether takes from each engine

| Concern | Source | Ether decision |
|---|---|---|
| GPU handles | Godot RID, bgfx handles | Opaque typed handle with index + generation; no raw backend objects |
| Resource lifetime | wgpu destroy + deferred free | Explicit `destroy`, deferred free until the referencing submissions complete |
| Scene hierarchy | Godot Node, O3DE TransformComponent | Hierarchical `SceneNode` with cached world transforms |
| Flat queries | Godot groups, Bevy ECS | Component queries plus tags/groups |
| Entity identity | Bevy | Index+generation `EntityId` |
| Component storage | Bevy archetype/sparse-set | Sparse sets for hot components, table storage for cold |
| Scheduling | Bevy stages, Filament frame | Explicit ordered stages; single-threaded first |
| Materials | Filament | Compiled material + lightweight instances |
| Renderer layering | wgpu | Types → API → core/validation → backend HAL |
| Passes | O3DE RPI, Filament post-process | Render graph with composable passes |
| Capabilities | bgfx Caps, wgpu features/limits | Feature flags + numeric limits + layout conventions |
| Assets | Godot UID, Bevy Assets, O3DE catalogs | Stable `AssetId` + catalog + handle + change events |
| Platform window | KorGE GameWindow | `expect`/`actual` platform boundary in a dedicated module |

## Where the references disagree, and Ether's choice

**Scene graph vs pure ECS.** Godot and O3DE make hierarchy primary; Bevy makes the ECS primary and
requires a separate hierarchy mechanism. Ether uses a hybrid: a hierarchical scene owns transforms,
visibility, and lifetime; an ECS world stores components keyed by the same entity ID. This keeps
transform math and parenting simple while allowing data-oriented systems for gameplay and
rendering. The cost is two representations to keep in sync, so the scene layer is the single owner
of hierarchy and the ECS never stores a second parent pointer.

**Reference counting vs explicit ownership.** Godot uses `RefCounted`; wgpu uses explicit
destroy/deferred free. Ether uses explicit ownership for GPU resources and scene nodes, with
`close()`/`destroy()` and a device-level deferred free queue. Kotlin GC is not a substitute for GPU
lifetime, because native memory is not traced.

**Wide server interface vs split interfaces.** Godot's `RenderingServer` is one large abstract
interface. Ether splits by domain (`Device`, `SwapChain`, `CommandEncoder`, `RenderGraph`) so that
backends and tests implement only what they need.

**Parallel ECS vs portable scheduling.** Bevy's executor is powerful but depends on Rust's
`Send`/`Sync` and unsafe access tracking. Ether starts single-threaded and exposes parallelism as a
future capability, because Kotlin/Wasm lacks shared-memory threads by default and Kotlin/Native's
concurrency model is stricter.

**Stateful immediate API vs recorded commands.** bgfx's `setState`/`submit` is compact but global.
Ether records commands into an encoder scoped to a frame and pass, then submits explicitly. This is
closer to wgpu/Filament and avoids global state.

## Non-goals confirmed by the research

- No reflection-heavy component descriptor system.
- No 16-bit index-only handles.
- No compile-time backend selection.
- No claim of platform or backend support without a build and a test on that platform.

# Rendering Architecture

## Status

**Foundation implemented; real GPU backends not started.** Phase 2 currently ships:

- `ether-render-api` — typed handles, descriptors, capabilities and limits, render-pass and command
  types, and the backend HAL (20 tests);
- `ether-render-core` — device facade, generational registry, validation, command encoder and
  translator, submission-token deferred free, linear render graph, and backend registry (77 tests);
- `ether-render-null` — headless backend with deterministic automatic/manual completion (8 tests).

Everything marked **implemented** below is compiled and tested on JVM, Linux x64, and headless Wasm.
Vulkan, WebGL2, Metal, and WebGPU backends, window/surface/swap-chain orchestration, shader
compilation, bind groups, compute passes, and render-graph compilation are **not implemented**. The
remaining design material is labeled as planned rather than presented as working code.

The research basis is in [ENGINE_RESEARCH.md](ENGINE_RESEARCH.md). Key influences:

- wgpu's instance → adapter → device → queue flow and layered validation;
- Filament's compiled-material + instance model and portable command recording;
- bgfx's compact handles and numeric capability limits;
- O3DE/Filament pass composition;
- Godot's separation of high-level scene API from low-level device API (without copying its dual
  RID namespace).

## Principles

1. **No raw backend objects in the public API.** Callers hold opaque handles.
2. **Capabilities, not backend checks.** Engine features are gated by reported features and limits.
3. **Explicit lifetime.** GPU resources are destroyed explicitly; actual free is deferred until the
   submissions that may reference them have completed.
4. **Validate, then execute.** The core validates descriptors and handle states before a backend
   sees them. The null backend exercises the same path.
5. **Record, then submit.** Commands are recorded into an encoder and submitted once per frame.
   There is no global immediate-mode state.
6. **Honest support matrix.** A backend is listed as supported only after drawing a frame on its
   target platform in CI.

## Layering

```
┌─────────────────────────────────────────────────────────────┐
│ ether-scene / application                  (planned)         │
│ cameras, lights, materials, meshes, render extraction        │
└──────────────────────────────┬──────────────────────────────┘
                               │ RenderList (planned)
┌──────────────────────────────▼──────────────────────────────┐
│ ether-render-api                        (implemented)        │
│ handles, descriptors, enums, capabilities, commands, HAL     │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ ether-render-core                       (implemented)        │
│ device facade, validation, registry, encoder, linear graph   │
└──────────────────────────────┬──────────────────────────────┘
                               │ Backend HAL (backend-token commands)
        ┌──────────────────────┼──────────────────────┐
        ▼                      ▼                      ▼
  Null backend           Vulkan backend         WebGL2 backend
  (implemented)          (Linux x64)            (planned)
        │
        └── Metal / WebGPU backends (planned)
```

Rules:

- `ether-render-api` must not depend on scene, platform, or a backend.
- Backends implement a narrow HAL, receive validated descriptors and translated commands, and never
  see scene types or the core's generational handles.
- `ether-render-core` owns validation and handle tables; backends own native objects and opaque
  tokens.
- The null backend implements the full HAL and is always available; it must be registered explicitly.

## Object model

| Concept | Status | Responsibility |
|---|---|---|
| `RenderBackend` / `RenderBackendDevice` | Implemented | HAL entry point, availability, capabilities, devices, submission completion |
| `GraphicsDevice` | Implemented | Creates resources, pipelines, encoders; owns registry and deferred-free queue |
| `CommandEncoder` / `CommandBuffer` | Implemented | Validated recording and single-use submission |
| `RenderGraph` | Implemented (linear) | Declares ordered passes and explicit texture reads |
| `ResourceRegistry` | Implemented (internal) | Maps generational handles to backend tokens and lifetimes |
| `GraphicsInstance` | Planned | Enumerates adapters and creates surfaces |
| `GraphicsAdapter` | Planned | Physical device info and device creation |
| `GraphicsQueue` | Planned | Submission and presentation |
| `Surface` / `SwapChain` | Planned | Platform window binding and presentation images |
| `BindGroup` | Planned | Shader resource binding |

There is one handle namespace per resource kind, all owned by a device. No second "server" namespace.

### Lifecycle

Implemented path:

```
RenderBackend.isAvailable() / capabilities()
  └─ RenderBackendRegistry.select(preference)
       └─ GraphicsDevice.create(backend)
            ├─ resources + pipelines
            ├─ command encoders → command buffers
            ├─ submit → backend submission token
            ├─ isSubmissionComplete(token) → release deferred resources
            └─ waitIdle() / close()
```

Planned full path:

```
Instance
  └─ enumerate adapters
       └─ Adapter + Surface
            └─ Device + Queue
                 └─ swap chain → acquire → render → present
```

Teardown is explicit and ordered. The current device destroys resources, waits for the backend, and
closes it. The planned presentation path adds swap chain → command buffers → pipelines → resources →
queue → device → surface → instance. The device records the first violation as a validation error.

## Capabilities and limits

### Features

Features are an enum set, not a bitmask of backend quirks. A backend reports a feature only if it
can execute it. The enum below is the implemented API:

```kotlin
public enum class GraphicsFeature {
    ComputeShaders,
    TimestampQueries,
    IndirectDraw,
    MultiDrawIndirect,
    InstancedDrawing,
    TextureCompressionBc,
    TextureCompressionEtc2,
    TextureCompressionAstc,
    Float16Shader,
    Float32Filterable,
    DualSourceBlending,
    StorageBuffers,
    StorageTextures,
    RenderTo3DTexture,
    MultisampleResolve,
    AnisotropicFiltering,
    DepthClamp,
    ClipDistances,
    WireframeFill,
}
```

### Limits

```kotlin
public data class GraphicsLimits(
    val maxTexture1DSize: Int = 2048,
    val maxTexture2DSize: Int = 2048,
    val maxTexture3DSize: Int = 256,
    val maxTextureCubeSize: Int = 2048,
    val maxTextureArrayLayers: Int = 256,
    val maxColorAttachments: Int = 4,
    val maxSampleCount: Int = 4,
    val maxVertexBuffers: Int = 16,
    val maxVertexAttributes: Int = 16,
    val maxVertexBufferStride: Int = 2048,
    val maxBindGroups: Int = 4,
    val maxBindingsPerGroup: Int = 16,
    val maxSampledTexturesPerStage: Int = 16,
    val maxSamplersPerStage: Int = 16,
    val maxUniformBuffersPerStage: Int = 12,
    val maxStorageBuffersPerStage: Int = 0,
    val maxUniformBufferBindingSize: Long = 16_384,
    val maxStorageBufferBindingSize: Long = 0,
    val maxBufferSize: Long = 268_435_456,
    val maxPushConstantSize: Int = 0,
    val maxComputeWorkgroupSizeX: Int = 0,
    val maxComputeWorkgroupSizeY: Int = 0,
    val maxComputeWorkgroupSizeZ: Int = 0,
    val maxComputeInvocations: Int = 0,
    val maxDrawCallsPerFrame: Int = 100_000,
    val maxFramesInFlight: Int = 2,
    val maxSamplerAnisotropy: Int = 16,
)
```

`GraphicsLimits.Conservative` is the default shown above. `GraphicsLimits.Permissive` is a synthetic
preset for the null backend and capability tests; it is not a hardware claim. Zero means the
corresponding operation is unsupported (or, for workgroup limits, absent).

### Layout conventions

These are not features; they are facts that shader and projection code must honor:

```kotlin
public data class GraphicsCapabilities(
    val backend: BackendKind,
    val features: Set<GraphicsFeature>,
    val limits: GraphicsLimits,
    val supportedTextureFormats: Set<TextureFormat>,
    val clipSpaceDepthZeroToOne: Boolean,
    val framebufferOriginBottomLeft: Boolean,
    val supportsMappableBuffers: Boolean,
    val supportsPersistentMappedBuffers: Boolean,
)
```

The type and its queries (`supports(feature)`, `supports(format)`) are implemented. Engine code
queries capabilities once and caches decisions. It must not branch on `BackendKind` except for
diagnostics and backend-specific workarounds that are documented and tested.

### Per-backend expectations (null backend and Linux x64 Vulkan implemented)

| Feature | Vulkan | WebGL2 | Metal | Null |
|---|---|---|---|---|
| Compute shaders | Yes | No (WebGL2) | Yes | Yes |
| Timestamp queries | Usually | No | Yes | Yes |
| Indirect draw | Yes | No | Yes | Yes |
| Instancing | Yes | Yes | Yes | Yes |
| BC/ETC2/ASTC | Format-dependent | No | Format-dependent | Yes |
| Float16 shader | Yes | Limited | Yes | Yes |
| Storage textures | Yes | No | Yes | Yes |
| Clip depth 0..1 | Yes | No ([-1,1]) | Yes | Yes |

The Null column is the implemented synthetic report: every feature and texture format, with
`GraphicsLimits.Permissive`. The Vulkan backend reports its measured live capabilities at device
creation (probed from the driver on Linux x64); the Linux smoke tests assert the Rgba8Unorm support,
the `[0, 1]` clip depth, and the top-left origin of that report. The WebGL2/Metal columns remain
expectations from the API specifications, not support claims.

## Handles and resources

Handles are typed value classes. There is no public generic handle and no `isValid` property; an
invalid handle fails when used.

```kotlin
@JvmInline public value class BufferHandle(public val raw: Long)
@JvmInline public value class TextureHandle(public val raw: Long)
@JvmInline public value class SamplerHandle(public val raw: Long)
@JvmInline public value class ShaderModuleHandle(public val raw: Long)
@JvmInline public value class RenderPipelineHandle(public val raw: Long)
@JvmInline public value class CommandBufferHandle(public val raw: Long)
```

Internally the core packs a 32-bit slot index and a 32-bit generation into `raw`. Callers must treat
the value as opaque. The registry validates:

- the slot is occupied;
- the generation matches;
- the resource kind matches the handle type;
- the resource has not been destroyed;
- the handle belongs to the device it is used with.

Handles are value types and can be stored in components and command buffers. They are not pointers.
Bind groups, compute pipelines, and query sets do not exist yet.

### Descriptors

Descriptors are immutable data classes. They are validated before backend creation:

```kotlin
public data class TextureDescriptor(
    val width: Int,
    val height: Int,
    val depthOrLayers: Int = 1,
    val mipLevels: Int = 1,
    val sampleCount: Int = 1,
    val format: TextureFormat,
    val usage: Set<TextureUsage>,
    val dimension: TextureDimension = TextureDimension.D2,
    val label: String? = null,
)
```

Validation checks nonzero dimensions, mip limits, sample counts, usage combinations, and capability
support. Mip-level limits are derived from the largest extent: array layers of a 2D texture do not
increase the mip count. Every resource carries an optional label for debugging.

## Command recording

```kotlin
public class CommandEncoder {
    public fun beginRenderPass(descriptor: RenderPassDescriptor)
    public fun endRenderPass()
    public fun setPipeline(pipeline: RenderPipelineHandle)
    public fun setVertexBuffer(slot: Int, buffer: BufferHandle, offsetBytes: Long = 0)
    public fun setIndexBuffer(buffer: BufferHandle, format: IndexFormat, offsetBytes: Long = 0)
    public fun setViewport(viewport: Viewport)
    public fun setScissor(scissor: ScissorRect)
    public fun draw(vertexCount: Int, instanceCount: Int = 1, firstVertex: Int = 0, firstInstance: Int = 0)
    public fun drawIndexed(indexCount: Int, instanceCount: Int = 1, firstIndex: Int = 0, baseVertex: Int = 0, firstInstance: Int = 0)
    public fun copyBufferToTexture(buffer: BufferHandle, bufferOffsetBytes: Long, bytesPerRow: Int, texture: TextureHandle, mipLevel: Int = 0, originX: Int = 0, originY: Int = 0, originZ: Int = 0, width: Int, height: Int = 1, depth: Int = 1)
    public fun finish(): CommandBuffer
}
```

Implemented rules:

- An encoder belongs to one device and one thread and is single-use.
- Passes are explicitly begun and ended; no implicit pass state.
- The encoder validates handle liveness, attachment usage/formats/dimensions/sample counts, pipeline
  compatibility, vertex and index ranges, viewport/scissor bounds, draw budget, instancing support,
  and copy layout/mip/usage before appending a command.
- At submit, `CommandTranslator` resolves handles to backend tokens and produces immutable
  `BackendCommand` lists. A command buffer is single-use and cannot cross devices.
- The null backend records the same backend command stream and performs no GPU work.
- `RenderBackendDevice.writeBuffer` uploads host bytes outside the command stream. The Vulkan
  backend writes non-uniform buffers directly through a persistent host mapping and stages
  `Uniform`/`Storage` writes through a device-local copy; the backend-local texture readback
  (`readTextureBytes`, backend device API only) is a test/diagnostic path that copies an image to a
  staging buffer through the same command queue. Bind groups, compute passes, indirect draw
  recording, and portable texture-to-buffer copies are not implemented yet.
- Bind groups, compute passes, indirect draw recording, and texture-to-buffer copies are not
  implemented yet.

## Render graph

**Implemented: linear execution.** The render graph currently provides an ordered pass list with
explicit texture reads. Graph compilation, culling, barriers, and transient resources are planned.

### Implemented model

```kotlin
public fun GraphicsDevice.createRenderGraph(
    name: String,
    configure: RenderGraphBuilder.() -> Unit,
): RenderGraph

public class RenderGraphBuilder {
    public fun importTexture(texture: TextureHandle): RenderGraphBuilder
    public fun pass(
        name: String,
        descriptor: RenderPassDescriptor,
        reads: Set<TextureHandle> = emptySet(),
        record: (CommandEncoder) -> Unit = {},
    ): RenderGraphBuilder
    public fun build(): RenderGraph
}

public class RenderGraph {
    public val passNames: List<String>
    public val passCount: Int
    public fun execute(encoder: CommandEncoder)
    public fun encode(device: GraphicsDevice): CommandBuffer
}
```

Builder rules:

- pass names must be non-blank and unique within the graph;
- every color/depth/resolve attachment is a write;
- a read must be imported or produced by an earlier pass;
- a pass cannot read a texture it also writes (no feedback loops);
- every referenced texture must be live on the graph's device;
- the graph must contain at least one pass.

Execution records each pass in declaration order, with an explicit begin/end pair. `encode` creates
a command buffer labelled `graph:<name>`.

### Planned compilation (not implemented)

1. Build a directed graph from pass reads/writes.
2. Cull passes whose outputs are not consumed and whose side effects are not required.
3. Topologically order the remaining passes; detect cycles and report the participating passes.
4. Derive resource states and barriers from read/write transitions.
5. Compute transient resource lifetimes; alias non-overlapping textures/buffers from a per-frame
   pool.
6. Produce an executable pass list.

The current API is intended to survive this optimization.

### Transient resources (planned)

- Transient textures/buffers are allocated from a per-frame pool.
- They never escape the frame.
- The pool grows to a high-water mark and is reused.
- Aliasing is optional and must be validated with debug markers in development builds.

None of this is implemented; every graph attachment is a persistent application-owned texture.

### Persistent resources

Persistent resources are created by the application or renderer subsystems (shadow maps, IBL
textures, history buffers). They are explicit handles and survive frames. The graph treats them as
imported externals when read, and as pass attachments when written.

## Synchronization

**Implemented today:**

- The core validates graph dependencies at build time and records passes in declaration order.
- Backends receive the translated command stream only; there is no barrier concept in the HAL yet.
- Resource lifetime is synchronized by submission completion tokens, not by barriers.

**Planned:**

- The render graph derives barriers from declared reads/writes.
- The backend executes barriers; callers do not write them by hand.
- Frames in flight use per-frame command buffers and per-frame uniform/storage rings.
- Swap-chain acquire/present semantics are backend-specific:
  - Vulkan: acquire image, render, present; handle out-of-date by recreating the swap chain.
  - WebGL2: the default framebuffer is presented by the browser; resize reconfigures the canvas.
  - Metal: acquire drawable, render, present.

## Shader pipeline

**Not implemented.** `ShaderModuleDescriptor` currently carries opaque bytes plus a stage and an
entry point name. The core validates the stage and pipeline shader-stage pairing, and the null
backend records the module; nothing is compiled, reflected, or translated.

### Planned scope

- One shader language for authoring: a restricted GLSL subset, compiled offline.
- SPIR-V is the canonical intermediate representation for Vulkan.
- WebGL2 consumes GLSL ES 3.00; Metal consumes MSL.
- The build pipeline compiles GLSL → SPIR-V, then reflects and translates as needed
  (SPIRV-Cross for GLSL ES/MSL).
- Shader modules declare entry points, stage, and resource bindings.
- Pipeline creation validates that the pipeline layout matches the shader reflection.

This is a toolchain, not a runtime dependency. The engine must not require a compiler at runtime.

### Material model (planned)

Following Filament:

- A `Material` is a compiled shader package plus pipeline state and parameter layout.
- A `MaterialInstance` references a material and owns parameter values (textures, uniforms,
  constants).
- Material variants (skin, morph, shadow, etc.) are compiled ahead of time or asynchronously with a
  placeholder fallback.
- Parameter blocks are uploaded through per-frame rings, not per-draw allocations.

### Binding model (planned)

- Explicit bind groups (wgpu-style) with a stable layout.
- Bind group 0: frame constants (camera, time, lighting environment).
- Bind group 1: pass constants.
- Bind group 2: material constants.
- Bind group 3: per-object constants, when not using push constants.
- Push constants are used only when the backend supports them and the size fits the limit.

## Resource lifetime

```
create() ──> alive ──> destroy() ──> pending-free ──> last referencing submission completes ──> freed
                │                          │
                └──────── use ──────────────┘
```

Implemented rules:

- `destroy()` marks the resource destroyed. If no submission references it, the backend token is
  destroyed immediately.
- Otherwise the resource stays alive until every submission that referenced it reports completion
  through `RenderBackendDevice.isSubmissionComplete(token)`.
- The core tracks per-submission resource references; a resource is freed when its last reference is
  released.
- Use after `destroy()` is a validation error. A new submission that references a destroyed resource
  fails during translation instead of extending its lifetime.
- `pollCompletedSubmissions()` releases deferred resources whose submissions have finished;
  `waitIdle()` and `close()` drain everything.
- `waitIdle()` is for teardown and tools; it must not be used per frame.
- The backend owns the completion signal and must never report completion early.

Planned:

- Swap-chain images are owned by the swap chain, not by the caller.
- Frame pacing and per-frame resource rings may bound how long deferred resources stay alive.

## Presentation and resize (planned)

- Surface creation is platform-specific; it is the only place where a native window handle enters
  the renderer.
- Resize is explicit: `swapChain.resize(width, height)`.
- Zero-size surfaces are legal while minimized; rendering is skipped.
- Out-of-date/acquire failures are recoverable and reported as results, not fatal exceptions.
- Device loss is fatal to the device; the application must recreate it.

None of this exists yet; the current core renders only into application-owned textures.

## Backend matrix

| Backend | Targets | Status | Validation requirement |
|---|---|---|---|
| Null | all | Implemented, tested (8 backend tests + 77 core tests) | Validation and lifetime behavior; no GPU work |
| Vulkan 1.3 | linuxX64, Android | Implemented on linuxX64, tested (3 smoke tests on RADV PHOENIX); Android not started | Draw a frame on linuxX64 done via smoke test; Android on device/emulator pending |
| WebGL2 | wasmJs, js | Not started | Draw a frame in Chromium via CI |
| Metal | macOS, iOS | Not started (later) | Draw a frame on macOS CI |
| WebGPU | wasmJs, js | Not started (optional, later) | Browser feature detection + frame |

The null backend remains registered by callers and validates the full command stream without GPU
work. The Linux x64 Vulkan backend rasterizes into application-owned offscreen textures and is
covered by real-device smoke tests (verified on an AMD RADV PHOENIX device). No backend presents to
a window: there is still no surface, swap-chain, or platform layer, so Ether produces no visual
output outside offscreen readbacks.

## Testing strategy

### Unit tests (no GPU) — implemented

- Handle validation: stale generation, wrong kind, destroyed resource, cross-device use.
- Descriptor validation: zero sizes, invalid mip counts, format/usage pairing, unsupported features
  and limits.
- Capability negotiation: feature gating and numeric limit checks.
- Render graph: ordered recording, unknown reads, feedback loops, duplicate and blank names, device
  scoping.
- Deferred free: a resource is freed only after the last submission referencing it completes.
- Command encoding: recorded command sequence and eager state validation.
- Backend selection: preference order, availability fallback, duplicate registration.

Suite sizes: `ether-render-api` 20 tests, `ether-render-core` 77 tests, `ether-render-null` 8
tests. All run on JVM, Linux x64, and headless Wasm.

Not covered, because the behavior does not exist yet: pass culling, barrier derivation, transient
resource lifetime, and real GPU execution.

### Null backend tests

The null backend executes the same validation and command recording as real backends, but produces
no GPU work. It can run on JVM, native, and Wasm in CI. It is the primary test seam. Its
`NullCompletionMode.Manual` mode makes submission completion explicit, which is how deferred free
is verified deterministically.

### Backend smoke tests (required before any support claim)

Each real backend must pass a smoke test:

1. create instance, adapter, device, surface;
2. create a triangle pipeline and buffer;
3. render one frame to a swap-chain image or offscreen target;
4. read back a pixel and assert its color;
5. resize and render again;
6. destroy all resources and verify no validation errors.

### Golden images (later)

Offscreen renders with deterministic inputs and a fixed camera. Compare with a tolerance. Golden
images are backend-specific; shared references are only used for simple flat-color tests.

## Performance considerations

- Frames in flight: 2 by default, 3 where latency allows.
- Per-frame rings for uniform/storage data; no per-draw allocation.
- Pipeline cache keyed by a hash of descriptor + shader variants.
- Bind group caching by layout and resource set.
- Draw-call sorting by pipeline and material to reduce state changes.
- Instancing for repeated meshes; multi-draw when supported.
- Avoid GPU readback in the frame loop; readback is async and budgeted.
- Validation is compiled in by default in debug, and can be disabled in release.

These are design targets, not measured results. None of the optimizations below are implemented yet;
the current core validates everything and executes a linear pass list.

## Explicit non-goals for Phase 2

- Ray tracing.
- Mesh shaders.
- Variable-rate shading beyond reporting it as a capability.
- Bindless rendering as a requirement.
- A visual shader editor.
- Automatic performance tuning.

## Risks

| Risk | Mitigation |
|---|---|
| Vulkan is a large first backend | Start with a triangle, then a lit cube; grow incrementally |
| WebGL2 feature gaps break the abstraction | Capability gates + a WebGL2-specific pipeline path |
| Shader translation differences | Canonical IR + per-backend tests + golden images |
| Deferred-free bugs | Null backend tests + validation + `waitIdle` at teardown |
| Null backend diverges from real backends | Keep the HAL narrow, add per-backend smoke tests before any support claim |
| Metal untestable on Linux | macOS CI before any support claim |
| Over-abstracting before a second backend | Implement Vulkan and null first, then extract common patterns |

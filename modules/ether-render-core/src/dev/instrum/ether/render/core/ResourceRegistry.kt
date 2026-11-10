package dev.instrum.ether.render.core

import dev.instrum.ether.render.api.BufferDescriptor
import dev.instrum.ether.render.api.BufferHandle
import dev.instrum.ether.render.api.BufferUsage
import dev.instrum.ether.render.api.GraphicsCapabilities
import dev.instrum.ether.render.api.GraphicsFeature
import dev.instrum.ether.render.api.RenderBackendDevice
import dev.instrum.ether.render.api.RenderPipelineDescriptor
import dev.instrum.ether.render.api.RenderPipelineHandle
import dev.instrum.ether.render.api.SamplerDescriptor
import dev.instrum.ether.render.api.SamplerHandle
import dev.instrum.ether.render.api.ShaderModuleDescriptor
import dev.instrum.ether.render.api.ShaderModuleHandle
import dev.instrum.ether.render.api.ShaderStage
import dev.instrum.ether.render.api.TextureDescriptor
import dev.instrum.ether.render.api.TextureDimension
import dev.instrum.ether.render.api.TextureFormat
import dev.instrum.ether.render.api.TextureHandle
import dev.instrum.ether.render.api.TextureUsage
import dev.instrum.ether.render.core.error.InvalidRenderResourceException
import dev.instrum.ether.render.core.error.RenderException
import dev.instrum.ether.render.core.error.RenderValidationException

/** The type of a device resource. */
internal enum class ResourceKind(val displayName: String) {
    Buffer("buffer"),
    Texture("texture"),
    Sampler("sampler"),
    ShaderModule("shader module"),
    RenderPipeline("render pipeline"),
}

/** Identity of a device resource: kind plus generational handle. */
internal data class ResourceKey(
    val kind: ResourceKind,
    val raw: Long,
)

/** Base state shared by every device resource. */
internal abstract class Resource(
    val key: ResourceKey,
    val token: Long,
) {
    /** Number of submitted command batches that may still reference this resource. */
    var refsInFlight: Int = 0

    /** Whether the owner has requested destruction. */
    var pendingDestroy: Boolean = false
}

internal class BufferResource(
    key: ResourceKey,
    token: Long,
    val descriptor: BufferDescriptor,
) : Resource(key, token)

internal class TextureResource(
    key: ResourceKey,
    token: Long,
    val descriptor: TextureDescriptor,
) : Resource(key, token)

internal class SamplerResource(
    key: ResourceKey,
    token: Long,
    val descriptor: SamplerDescriptor,
) : Resource(key, token)

internal class ShaderModuleResource(
    key: ResourceKey,
    token: Long,
    val descriptor: ShaderModuleDescriptor,
) : Resource(key, token)

internal class RenderPipelineResource(
    key: ResourceKey,
    token: Long,
    val descriptor: RenderPipelineDescriptor,
    val vertexShader: ShaderModuleResource,
    val fragmentShader: ShaderModuleResource?,
) : Resource(key, token)

/**
 * Owns generational handle tables and the mapping from handles to backend tokens.
 *
 * The registry validates every descriptor against the device capabilities before calling the
 * backend. It also defers backend destruction while submissions that reference a resource are in
 * flight.
 */
internal class ResourceRegistry(
    private val backend: RenderBackendDevice,
    private val capabilities: GraphicsCapabilities,
) {
    private val bufferHandles = HandleTable("buffer")
    private val textureHandles = HandleTable("texture")
    private val samplerHandles = HandleTable("sampler")
    private val shaderHandles = HandleTable("shader module")
    private val pipelineHandles = HandleTable("render pipeline")

    private val resources: MutableMap<ResourceKey, Resource> = LinkedHashMap()

    /** Number of resources that have not been destroyed yet. */
    val liveResourceCount: Int
        get() = resources.size

    fun createBuffer(descriptor: BufferDescriptor): BufferHandle {
        CapabilityValidation.validateBuffer(capabilities, descriptor)
        val raw = bufferHandles.allocate()
        val token = try {
            backend.createBuffer(descriptor)
        } catch (error: Throwable) {
            bufferHandles.free(raw)
            throw error
        }
        val key = ResourceKey(ResourceKind.Buffer, raw)
        resources[key] = BufferResource(key, token, descriptor)
        return BufferHandle(raw)
    }

    fun createTexture(descriptor: TextureDescriptor): TextureHandle {
        CapabilityValidation.validateTexture(capabilities, descriptor)
        val raw = textureHandles.allocate()
        val token = try {
            backend.createTexture(descriptor)
        } catch (error: Throwable) {
            textureHandles.free(raw)
            throw error
        }
        val key = ResourceKey(ResourceKind.Texture, raw)
        resources[key] = TextureResource(key, token, descriptor)
        return TextureHandle(raw)
    }

    fun createSampler(descriptor: SamplerDescriptor): SamplerHandle {
        CapabilityValidation.validateSampler(capabilities, descriptor)
        val raw = samplerHandles.allocate()
        val token = try {
            backend.createSampler(descriptor)
        } catch (error: Throwable) {
            samplerHandles.free(raw)
            throw error
        }
        val key = ResourceKey(ResourceKind.Sampler, raw)
        resources[key] = SamplerResource(key, token, descriptor)
        return SamplerHandle(raw)
    }

    fun createShaderModule(descriptor: ShaderModuleDescriptor): ShaderModuleHandle {
        CapabilityValidation.validateShaderModule(capabilities, descriptor)
        val raw = shaderHandles.allocate()
        val token = try {
            backend.createShaderModule(descriptor)
        } catch (error: Throwable) {
            shaderHandles.free(raw)
            throw error
        }
        val key = ResourceKey(ResourceKind.ShaderModule, raw)
        resources[key] = ShaderModuleResource(key, token, descriptor)
        return ShaderModuleHandle(raw)
    }

    fun createRenderPipeline(descriptor: RenderPipelineDescriptor): RenderPipelineHandle {
        CapabilityValidation.validateRenderPipeline(capabilities, descriptor)
        val vertexShader = resolveShaderModule(descriptor.vertex.shader)
        if (vertexShader.descriptor.stage != ShaderStage.Vertex) {
            throw RenderValidationException(
                "Render pipeline vertex shader must be a vertex-stage module, was " +
                    "${vertexShader.descriptor.stage}",
            )
        }
        val fragmentShader = descriptor.fragment?.let { fragment ->
            val shader = resolveShaderModule(fragment.shader)
            if (shader.descriptor.stage != ShaderStage.Fragment) {
                throw RenderValidationException(
                    "Render pipeline fragment shader must be a fragment-stage module, was " +
                        "${shader.descriptor.stage}",
                )
            }
            shader
        }

        val raw = pipelineHandles.allocate()
        val token = try {
            backend.createRenderPipeline(
                descriptor = descriptor,
                vertexShaderToken = vertexShader.token,
                fragmentShaderToken = fragmentShader?.token,
            )
        } catch (error: Throwable) {
            pipelineHandles.free(raw)
            throw error
        }
        val key = ResourceKey(ResourceKind.RenderPipeline, raw)
        resources[key] = RenderPipelineResource(
            key = key,
            token = token,
            descriptor = descriptor,
            vertexShader = vertexShader,
            fragmentShader = fragmentShader,
        )
        return RenderPipelineHandle(raw)
    }

    fun resolveBuffer(handle: BufferHandle): BufferResource =
        resolve(ResourceKey(ResourceKind.Buffer, handle.raw)) as? BufferResource
            ?: throw invalid(ResourceKind.Buffer, handle.raw)

    /** Writes [data] into the buffer behind [handle] through the backend. */
    fun writeBuffer(handle: BufferHandle, offsetBytes: Long, data: ByteArray) {
        val resource = resolveBuffer(handle)
        if (offsetBytes < 0L) {
            throw RenderValidationException("Buffer write offset must not be negative")
        }
        if (offsetBytes + data.size.toLong() > resource.descriptor.sizeBytes) {
            throw RenderValidationException(
                "Write of ${data.size} bytes at offset $offsetBytes exceeds the " +
                    "${resource.descriptor.sizeBytes}-byte buffer",
            )
        }
        backend.writeBuffer(resource.token, offsetBytes, data)
    }

    fun resolveTexture(handle: TextureHandle): TextureResource =
        resolve(ResourceKey(ResourceKind.Texture, handle.raw)) as? TextureResource
            ?: throw invalid(ResourceKind.Texture, handle.raw)

    fun resolveSampler(handle: SamplerHandle): SamplerResource =
        resolve(ResourceKey(ResourceKind.Sampler, handle.raw)) as? SamplerResource
            ?: throw invalid(ResourceKind.Sampler, handle.raw)

    fun resolveShaderModule(handle: ShaderModuleHandle): ShaderModuleResource =
        resolve(ResourceKey(ResourceKind.ShaderModule, handle.raw)) as? ShaderModuleResource
            ?: throw invalid(ResourceKind.ShaderModule, handle.raw)

    fun resolveRenderPipeline(handle: RenderPipelineHandle): RenderPipelineResource =
        resolve(ResourceKey(ResourceKind.RenderPipeline, handle.raw)) as? RenderPipelineResource
            ?: throw invalid(ResourceKind.RenderPipeline, handle.raw)

    /** Resolves a key during command translation, rejecting stale or pending resources. */
    fun resolveForSubmit(key: ResourceKey): Resource = resolve(key)

    /** Marks [resourceList] as referenced by one in-flight submission. */
    fun retain(resourceList: Collection<Resource>) {
        resourceList.forEach { it.refsInFlight++ }
    }

    /** Releases one submission reference and destroys resources whose destruction is pending. */
    fun release(resourceList: Collection<Resource>) {
        resourceList.forEach { resource ->
            if (resource.refsInFlight <= 0) {
                throw RenderException(
                    "Internal error: releasing ${resource.key.kind.displayName} with no in-flight reference",
                )
            }
            resource.refsInFlight--
            if (resource.refsInFlight == 0 && resource.pendingDestroy) {
                destroyNow(resource)
            }
        }
    }

    fun destroyBuffer(handle: BufferHandle) = requestDestroy(resolveBuffer(handle))

    fun destroyTexture(handle: TextureHandle) = requestDestroy(resolveTexture(handle))

    fun destroySampler(handle: SamplerHandle) = requestDestroy(resolveSampler(handle))

    fun destroyShaderModule(handle: ShaderModuleHandle) = requestDestroy(resolveShaderModule(handle))

    fun destroyRenderPipeline(handle: RenderPipelineHandle) =
        requestDestroy(resolveRenderPipeline(handle))

    /**
     * Destroys every remaining resource, collecting the first failure.
     *
     * Cleanup continues after a backend failure so that one broken token does not leak every other
     * resource.
     */
    fun destroyAll() {
        var firstFailure: Throwable? = null
        resources.values.toList().forEach { resource ->
            resource.pendingDestroy = true
            try {
                backend.destroy(resource.token)
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
            }
        }
        resources.clear()
        firstFailure?.let { failure ->
            throw RenderException("Failed to destroy one or more render resources", failure)
        }
    }

    private fun requestDestroy(resource: Resource) {
        if (resource.pendingDestroy) {
            throw InvalidRenderResourceException(
                "${resource.key.kind.displayName} handle 0x${resource.key.raw.toULong().toString(16)} has already been destroyed",
            )
        }
        resource.pendingDestroy = true
        if (resource.refsInFlight == 0) {
            destroyNow(resource)
        }
    }

    private fun destroyNow(resource: Resource) {
        backend.destroy(resource.token)
        resources.remove(resource.key)
        tableFor(resource.key.kind).free(resource.key.raw)
    }

    private fun resolve(key: ResourceKey): Resource {
        val resource = resources[key]
            ?: throw InvalidRenderResourceException(
                "${key.kind.displayName} handle 0x${key.raw.toULong().toString(16)} is invalid or has been destroyed",
            )
        if (resource.pendingDestroy) {
            throw InvalidRenderResourceException(
                "${key.kind.displayName} handle 0x${key.raw.toULong().toString(16)} has been destroyed",
            )
        }
        return resource
    }

    private fun tableFor(kind: ResourceKind): HandleTable = when (kind) {
        ResourceKind.Buffer -> bufferHandles
        ResourceKind.Texture -> textureHandles
        ResourceKind.Sampler -> samplerHandles
        ResourceKind.ShaderModule -> shaderHandles
        ResourceKind.RenderPipeline -> pipelineHandles
    }

    private fun invalid(kind: ResourceKind, raw: Long): InvalidRenderResourceException =
        InvalidRenderResourceException(
            "${kind.displayName} handle 0x${raw.toULong().toString(16)} is invalid or has been destroyed",
        )
}

/**
 * Descriptor checks shared by resource creation and pipeline creation.
 */
internal object CapabilityValidation {
    fun validateBuffer(capabilities: GraphicsCapabilities, descriptor: BufferDescriptor) {
        val limits = capabilities.limits
        if (descriptor.sizeBytes > limits.maxBufferSize) {
            throw RenderValidationException(
                "Buffer size ${descriptor.sizeBytes} exceeds maxBufferSize ${limits.maxBufferSize}",
            )
        }
        if (BufferUsage.Storage in descriptor.usage &&
            !capabilities.supports(GraphicsFeature.StorageBuffers)
        ) {
            throw RenderValidationException(
                "Storage buffers are not supported by ${capabilities.backend}",
            )
        }
        if (BufferUsage.Indirect in descriptor.usage &&
            !capabilities.supports(GraphicsFeature.IndirectDraw)
        ) {
            throw RenderValidationException(
                "Indirect buffers are not supported by ${capabilities.backend}",
            )
        }
        if (BufferUsage.Uniform in descriptor.usage &&
            descriptor.sizeBytes > limits.maxUniformBufferBindingSize
        ) {
            throw RenderValidationException(
                "Uniform buffer size ${descriptor.sizeBytes} exceeds " +
                    "maxUniformBufferBindingSize ${limits.maxUniformBufferBindingSize}",
            )
        }
        if (BufferUsage.Storage in descriptor.usage &&
            descriptor.sizeBytes > limits.maxStorageBufferBindingSize
        ) {
            throw RenderValidationException(
                "Storage buffer size ${descriptor.sizeBytes} exceeds " +
                    "maxStorageBufferBindingSize ${limits.maxStorageBufferBindingSize}",
            )
        }
    }

    fun validateTexture(capabilities: GraphicsCapabilities, descriptor: TextureDescriptor) {
        val limits = capabilities.limits
        if (!capabilities.supports(descriptor.format)) {
            throw RenderValidationException(
                "Texture format ${descriptor.format} is not supported by ${capabilities.backend}",
            )
        }
        when (descriptor.dimension) {
            TextureDimension.D1 -> {
                if (descriptor.width > limits.maxTexture1DSize) {
                    throw RenderValidationException(
                        "1D texture width ${descriptor.width} exceeds maxTexture1DSize ${limits.maxTexture1DSize}",
                    )
                }
            }

            TextureDimension.D2 -> {
                if (descriptor.width > limits.maxTexture2DSize ||
                    descriptor.height > limits.maxTexture2DSize
                ) {
                    throw RenderValidationException(
                        "2D texture ${descriptor.width}x${descriptor.height} exceeds maxTexture2DSize ${limits.maxTexture2DSize}",
                    )
                }
                if (descriptor.depthOrLayers > limits.maxTextureArrayLayers) {
                    throw RenderValidationException(
                        "Texture array layers ${descriptor.depthOrLayers} exceed maxTextureArrayLayers ${limits.maxTextureArrayLayers}",
                    )
                }
            }

            TextureDimension.D3 -> {
                if (descriptor.width > limits.maxTexture3DSize ||
                    descriptor.height > limits.maxTexture3DSize ||
                    descriptor.depthOrLayers > limits.maxTexture3DSize
                ) {
                    throw RenderValidationException(
                        "3D texture ${descriptor.width}x${descriptor.height}x${descriptor.depthOrLayers} exceeds maxTexture3DSize ${limits.maxTexture3DSize}",
                    )
                }
            }
        }
        if (descriptor.sampleCount > limits.maxSampleCount) {
            throw RenderValidationException(
                "Sample count ${descriptor.sampleCount} exceeds maxSampleCount ${limits.maxSampleCount}",
            )
        }
        if (TextureUsage.Storage in descriptor.usage &&
            !capabilities.supports(GraphicsFeature.StorageTextures)
        ) {
            throw RenderValidationException(
                "Storage textures are not supported by ${capabilities.backend}",
            )
        }
        val isAttachment = TextureUsage.RenderTarget in descriptor.usage ||
            TextureUsage.DepthStencil in descriptor.usage
        if (descriptor.dimension == TextureDimension.D3 && isAttachment &&
            !capabilities.supports(GraphicsFeature.RenderTo3DTexture)
        ) {
            throw RenderValidationException(
                "Rendering to 3D textures is not supported by ${capabilities.backend}",
            )
        }
    }

    fun validateSampler(capabilities: GraphicsCapabilities, descriptor: SamplerDescriptor) {
        if (descriptor.maxAnisotropy > 1 &&
            !capabilities.supports(GraphicsFeature.AnisotropicFiltering)
        ) {
            throw RenderValidationException(
                "Anisotropic filtering is not supported by ${capabilities.backend}",
            )
        }
        if (descriptor.maxAnisotropy > capabilities.limits.maxSamplerAnisotropy) {
            throw RenderValidationException(
                "maxAnisotropy ${descriptor.maxAnisotropy} exceeds maxSamplerAnisotropy " +
                    capabilities.limits.maxSamplerAnisotropy,
            )
        }
    }

    fun validateShaderModule(capabilities: GraphicsCapabilities, descriptor: ShaderModuleDescriptor) {
        if (descriptor.stage == ShaderStage.Compute &&
            !capabilities.supports(GraphicsFeature.ComputeShaders)
        ) {
            throw RenderValidationException(
                "Compute shaders are not supported by ${capabilities.backend}",
            )
        }
    }

    fun validateRenderPipeline(
        capabilities: GraphicsCapabilities,
        descriptor: RenderPipelineDescriptor,
    ) {
        val limits = capabilities.limits
        if (descriptor.vertex.buffers.size > limits.maxVertexBuffers) {
            throw RenderValidationException(
                "Pipeline declares ${descriptor.vertex.buffers.size} vertex buffers, " +
                    "maxVertexBuffers is ${limits.maxVertexBuffers}",
            )
        }
        val attributeCount = descriptor.vertex.buffers.sumOf { it.attributes.size }
        if (attributeCount > limits.maxVertexAttributes) {
            throw RenderValidationException(
                "Pipeline declares $attributeCount vertex attributes, " +
                    "maxVertexAttributes is ${limits.maxVertexAttributes}",
            )
        }
        descriptor.vertex.buffers.forEachIndexed { index, layout ->
            if (layout.strideBytes > limits.maxVertexBufferStride) {
                throw RenderValidationException(
                    "Vertex buffer $index stride ${layout.strideBytes} exceeds " +
                        "maxVertexBufferStride ${limits.maxVertexBufferStride}",
                )
            }
        }
        val targets = descriptor.fragment?.targets.orEmpty()
        if (targets.size > limits.maxColorAttachments) {
            throw RenderValidationException(
                "Pipeline declares ${targets.size} color targets, " +
                    "maxColorAttachments is ${limits.maxColorAttachments}",
            )
        }
        targets.forEachIndexed { index, target ->
            if (!capabilities.supports(target.format)) {
                throw RenderValidationException(
                    "Color target $index format ${target.format} is not supported by " +
                        capabilities.backend,
                )
            }
        }
        descriptor.depthStencil?.let { depth ->
            if (!capabilities.supports(depth.format)) {
                throw RenderValidationException(
                    "Depth format ${depth.format} is not supported by ${capabilities.backend}",
                )
            }
        }
        if (descriptor.multisample.count > limits.maxSampleCount) {
            throw RenderValidationException(
                "Pipeline sample count ${descriptor.multisample.count} exceeds " +
                    "maxSampleCount ${limits.maxSampleCount}",
            )
        }
        if (descriptor.primitive.unclippedDepth &&
            !capabilities.supports(GraphicsFeature.DepthClamp)
        ) {
            throw RenderValidationException(
                "Unclipped depth is not supported by ${capabilities.backend}",
            )
        }
        if (targets.isEmpty() && descriptor.depthStencil == null) {
            throw RenderValidationException(
                "A render pipeline must write at least one color target or a depth attachment",
            )
        }
    }
}

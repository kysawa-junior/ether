package dev.instrum.ether.render.core

import dev.instrum.ether.render.api.BackendColorAttachment
import dev.instrum.ether.render.api.BackendCommand
import dev.instrum.ether.render.api.BackendDepthStencilAttachment
import dev.instrum.ether.render.api.BackendRenderPassDescriptor
import dev.instrum.ether.render.api.RenderCommand
import dev.instrum.ether.render.api.RenderPassDescriptor
import dev.instrum.ether.render.core.error.RenderValidationException

/** A translated command buffer: backend commands plus the resources they reference. */
internal class TranslatedCommandBuffer(
    val commands: List<BackendCommand>,
    val resources: List<Resource>,
)

/**
 * Translates portable handle-based commands into backend token commands.
 *
 * Translation resolves every handle against the live registry, so a resource destroyed after
 * encoding but before submission is reported deterministically.
 */
internal class CommandTranslator(private val registry: ResourceRegistry) {
    private val referenced: MutableMap<ResourceKey, Resource> = LinkedHashMap()

    fun translate(commands: List<RenderCommand>): TranslatedCommandBuffer {
        referenced.clear()
        val translated = commands.map(::translateCommand)
        return TranslatedCommandBuffer(translated, referenced.values.toList())
    }

    private fun translateCommand(command: RenderCommand): BackendCommand = when (command) {
        is RenderCommand.BeginRenderPass -> BackendCommand.BeginRenderPass(
            translatePass(command.descriptor),
        )

        RenderCommand.EndRenderPass -> BackendCommand.EndRenderPass

        is RenderCommand.SetPipeline -> BackendCommand.SetPipeline(
            pipeline(command.pipeline.raw).token,
        )

        is RenderCommand.SetVertexBuffer -> BackendCommand.SetVertexBuffer(
            slot = command.slot,
            buffer = buffer(command.buffer.raw).token,
            offsetBytes = command.offsetBytes,
        )

        is RenderCommand.SetIndexBuffer -> BackendCommand.SetIndexBuffer(
            buffer = buffer(command.buffer.raw).token,
            format = command.format,
            offsetBytes = command.offsetBytes,
        )

        is RenderCommand.SetViewport -> BackendCommand.SetViewport(command.viewport)

        is RenderCommand.SetScissor -> BackendCommand.SetScissor(command.scissor)

        is RenderCommand.Draw -> BackendCommand.Draw(
            vertexCount = command.vertexCount,
            instanceCount = command.instanceCount,
            firstVertex = command.firstVertex,
            firstInstance = command.firstInstance,
        )

        is RenderCommand.DrawIndexed -> BackendCommand.DrawIndexed(
            indexCount = command.indexCount,
            instanceCount = command.instanceCount,
            firstIndex = command.firstIndex,
            baseVertex = command.baseVertex,
            firstInstance = command.firstInstance,
        )

        is RenderCommand.CopyBufferToTexture -> BackendCommand.CopyBufferToTexture(
            buffer = buffer(command.buffer.raw).token,
            bufferOffsetBytes = command.bufferOffsetBytes,
            bytesPerRow = command.bytesPerRow,
            texture = texture(command.texture.raw).token,
            mipLevel = command.mipLevel,
            originX = command.originX,
            originY = command.originY,
            originZ = command.originZ,
            width = command.width,
            height = command.height,
            depth = command.depth,
        )
    }

    private fun translatePass(descriptor: RenderPassDescriptor): BackendRenderPassDescriptor =
        BackendRenderPassDescriptor(
            label = descriptor.label,
            colorAttachments = descriptor.colorAttachments.map { attachment ->
                BackendColorAttachment(
                    texture = texture(attachment.texture.raw).token,
                    resolveTarget = attachment.resolveTarget?.let { target ->
                        texture(target.raw).token
                    },
                    load = attachment.load,
                    store = attachment.store,
                )
            },
            depthStencilAttachment = descriptor.depthStencilAttachment?.let { attachment ->
                BackendDepthStencilAttachment(
                    texture = texture(attachment.texture.raw).token,
                    depthLoad = attachment.depthLoad,
                    depthStore = attachment.depthStore,
                )
            },
        )

    private fun buffer(raw: Long): BufferResource {
        val resource = resolve(ResourceKind.Buffer, raw) as? BufferResource
            ?: throw invalid(ResourceKind.Buffer, raw)
        referenced[resource.key] = resource
        return resource
    }

    private fun texture(raw: Long): TextureResource {
        val resource = resolve(ResourceKind.Texture, raw) as? TextureResource
            ?: throw invalid(ResourceKind.Texture, raw)
        referenced[resource.key] = resource
        return resource
    }

    private fun pipeline(raw: Long): RenderPipelineResource {
        val resource = resolve(ResourceKind.RenderPipeline, raw) as? RenderPipelineResource
            ?: throw invalid(ResourceKind.RenderPipeline, raw)
        referenced[resource.key] = resource
        return resource
    }

    private fun resolve(kind: ResourceKind, raw: Long): Resource =
        registry.resolveForSubmit(ResourceKey(kind, raw))

    private fun invalid(kind: ResourceKind, raw: Long): RenderValidationException =
        RenderValidationException(
            "${kind.displayName} handle 0x${raw.toULong().toString(16)} is invalid while translating commands",
        )
}

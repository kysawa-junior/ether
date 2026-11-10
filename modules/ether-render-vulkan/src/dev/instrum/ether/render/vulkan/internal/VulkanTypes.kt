@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.instrum.ether.render.vulkan.internal

import dev.instrum.ether.render.api.AddressMode
import dev.instrum.ether.render.api.BlendFactor
import dev.instrum.ether.render.api.BlendOperation
import dev.instrum.ether.render.api.CompareFunction
import dev.instrum.ether.render.api.CullMode
import dev.instrum.ether.render.api.FilterMode
import dev.instrum.ether.render.api.FrontFace
import dev.instrum.ether.render.api.IndexFormat
import dev.instrum.ether.render.api.MipmapFilterMode
import dev.instrum.ether.render.api.PrimitiveTopology
import dev.instrum.ether.render.api.TextureFormat
import dev.instrum.ether.render.api.VertexFormat
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BLEND_FACTOR_CONSTANT_COLOR
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BLEND_FACTOR_DST_ALPHA
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BLEND_FACTOR_DST_COLOR
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BLEND_FACTOR_ONE
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BLEND_FACTOR_SRC_ALPHA
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BLEND_FACTOR_SRC_ALPHA_SATURATE
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BLEND_FACTOR_SRC_COLOR
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BLEND_FACTOR_ZERO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BLEND_OP_ADD
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BLEND_OP_MAX
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BLEND_OP_MIN
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BLEND_OP_REVERSE_SUBTRACT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BLEND_OP_SUBTRACT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_COMPARE_OP_ALWAYS
import dev.instrum.ether.render.vulkan.internal.vkc.VK_COMPARE_OP_EQUAL
import dev.instrum.ether.render.vulkan.internal.vkc.VK_COMPARE_OP_GREATER
import dev.instrum.ether.render.vulkan.internal.vkc.VK_COMPARE_OP_GREATER_OR_EQUAL
import dev.instrum.ether.render.vulkan.internal.vkc.VK_COMPARE_OP_LESS
import dev.instrum.ether.render.vulkan.internal.vkc.VK_COMPARE_OP_LESS_OR_EQUAL
import dev.instrum.ether.render.vulkan.internal.vkc.VK_COMPARE_OP_NEVER
import dev.instrum.ether.render.vulkan.internal.vkc.VK_COMPARE_OP_NOT_EQUAL
import dev.instrum.ether.render.vulkan.internal.vkc.VK_CULL_MODE_BACK_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_CULL_MODE_FRONT_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_CULL_MODE_NONE
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FILTER_LINEAR
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FILTER_NEAREST
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_B8G8R8A8_SRGB
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_B8G8R8A8_UNORM
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_D16_UNORM
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_D24_UNORM_S8_UINT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_D32_SFLOAT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_R16G16B16A16_SFLOAT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_R16G16B16A16_UINT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_R16G16_UINT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_R32G32B32A32_SFLOAT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_R32G32B32_SFLOAT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_R32G32_SFLOAT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_R32_SFLOAT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_R32_UINT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_R8G8B8A8_SRGB
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_R8G8B8A8_UINT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_R8G8B8A8_UNORM
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_R8G8_UNORM
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_R8_UNORM
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_X8_D24_UNORM_PACK32
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FRONT_FACE_CLOCKWISE
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FRONT_FACE_COUNTER_CLOCKWISE
import dev.instrum.ether.render.vulkan.internal.vkc.VK_INDEX_TYPE_UINT16
import dev.instrum.ether.render.vulkan.internal.vkc.VK_INDEX_TYPE_UINT32
import dev.instrum.ether.render.vulkan.internal.vkc.VK_PRIMITIVE_TOPOLOGY_LINE_LIST
import dev.instrum.ether.render.vulkan.internal.vkc.VK_PRIMITIVE_TOPOLOGY_LINE_STRIP
import dev.instrum.ether.render.vulkan.internal.vkc.VK_PRIMITIVE_TOPOLOGY_POINT_LIST
import dev.instrum.ether.render.vulkan.internal.vkc.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
import dev.instrum.ether.render.vulkan.internal.vkc.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP
import dev.instrum.ether.render.vulkan.internal.vkc.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
import dev.instrum.ether.render.vulkan.internal.vkc.VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_SAMPLER_ADDRESS_MODE_REPEAT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_SAMPLER_MIPMAP_MODE_LINEAR
import dev.instrum.ether.render.vulkan.internal.vkc.VK_SAMPLER_MIPMAP_MODE_NEAREST

/** Maps a portable texture format to its Vulkan format. */
internal fun TextureFormat.toVkFormat(): UInt = when (this) {
    TextureFormat.R8Unorm -> VK_FORMAT_R8_UNORM
    TextureFormat.Rg8Unorm -> VK_FORMAT_R8G8_UNORM
    TextureFormat.Rgba8Unorm -> VK_FORMAT_R8G8B8A8_UNORM
    TextureFormat.Rgba8Srgb -> VK_FORMAT_R8G8B8A8_SRGB
    TextureFormat.Bgra8Unorm -> VK_FORMAT_B8G8R8A8_UNORM
    TextureFormat.Bgra8Srgb -> VK_FORMAT_B8G8R8A8_SRGB
    TextureFormat.Rgba16Float -> VK_FORMAT_R16G16B16A16_SFLOAT
    TextureFormat.Rgba32Float -> VK_FORMAT_R32G32B32A32_SFLOAT
    TextureFormat.Depth16Unorm -> VK_FORMAT_D16_UNORM
    TextureFormat.Depth24Plus -> VK_FORMAT_X8_D24_UNORM_PACK32
    TextureFormat.Depth24PlusStencil8 -> VK_FORMAT_D24_UNORM_S8_UINT
    TextureFormat.Depth32Float -> VK_FORMAT_D32_SFLOAT
}

/** Maps a portable vertex format to its Vulkan vertex format. */
internal fun VertexFormat.toVkFormat(): UInt = when (this) {
    VertexFormat.Float32 -> VK_FORMAT_R32_SFLOAT
    VertexFormat.Float32x2 -> VK_FORMAT_R32G32_SFLOAT
    VertexFormat.Float32x3 -> VK_FORMAT_R32G32B32_SFLOAT
    VertexFormat.Float32x4 -> VK_FORMAT_R32G32B32A32_SFLOAT
    VertexFormat.UInt8x4 -> VK_FORMAT_R8G8B8A8_UINT
    VertexFormat.UInt16x2 -> VK_FORMAT_R16G16_UINT
    VertexFormat.UInt16x4 -> VK_FORMAT_R16G16B16A16_UINT
    VertexFormat.UInt32 -> VK_FORMAT_R32_UINT
}

/** Maps a portable index format to its Vulkan index type. */
internal fun IndexFormat.toVkIndexType(): UInt = when (this) {
    IndexFormat.UInt16 -> VK_INDEX_TYPE_UINT16
    IndexFormat.UInt32 -> VK_INDEX_TYPE_UINT32
}

/** Maps a portable topology to its Vulkan primitive topology. */
internal fun PrimitiveTopology.toVkTopology(): UInt = when (this) {
    PrimitiveTopology.TriangleList -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
    PrimitiveTopology.TriangleStrip -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP
    PrimitiveTopology.LineList -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST
    PrimitiveTopology.LineStrip -> VK_PRIMITIVE_TOPOLOGY_LINE_STRIP
    PrimitiveTopology.PointList -> VK_PRIMITIVE_TOPOLOGY_POINT_LIST
}

/** Maps a portable cull mode to its Vulkan cull mode flags. */
internal fun CullMode.toVkCullMode(): UInt = when (this) {
    CullMode.None -> VK_CULL_MODE_NONE
    CullMode.Front -> VK_CULL_MODE_FRONT_BIT
    CullMode.Back -> VK_CULL_MODE_BACK_BIT
}

/** Maps a portable front face to its Vulkan front face constant. */
internal fun FrontFace.toVkFrontFace(): UInt = when (this) {
    FrontFace.CounterClockwise -> VK_FRONT_FACE_COUNTER_CLOCKWISE
    FrontFace.Clockwise -> VK_FRONT_FACE_CLOCKWISE
}

/** Maps a portable compare function to its Vulkan compare operation. */
internal fun CompareFunction.toVkCompareOp(): UInt = when (this) {
    CompareFunction.Never -> VK_COMPARE_OP_NEVER
    CompareFunction.Less -> VK_COMPARE_OP_LESS
    CompareFunction.Equal -> VK_COMPARE_OP_EQUAL
    CompareFunction.LessEqual -> VK_COMPARE_OP_LESS_OR_EQUAL
    CompareFunction.Greater -> VK_COMPARE_OP_GREATER
    CompareFunction.NotEqual -> VK_COMPARE_OP_NOT_EQUAL
    CompareFunction.GreaterEqual -> VK_COMPARE_OP_GREATER_OR_EQUAL
    CompareFunction.Always -> VK_COMPARE_OP_ALWAYS
}

/** Maps a portable address mode to its Vulkan sampler address mode. */
internal fun AddressMode.toVkAddressMode(): UInt = when (this) {
    AddressMode.ClampToEdge -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
    AddressMode.Repeat -> VK_SAMPLER_ADDRESS_MODE_REPEAT
    AddressMode.MirrorRepeat -> VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT
}

/** Maps a portable filter mode to its Vulkan filter. */
internal fun FilterMode.toVkFilter(): UInt = when (this) {
    FilterMode.Nearest -> VK_FILTER_NEAREST
    FilterMode.Linear -> VK_FILTER_LINEAR
}

/** Maps a portable mipmap filter to its Vulkan sampler mipmap mode. */
internal fun MipmapFilterMode.toVkMipmapMode(): UInt = when (this) {
    MipmapFilterMode.Nearest -> VK_SAMPLER_MIPMAP_MODE_NEAREST
    MipmapFilterMode.Linear -> VK_SAMPLER_MIPMAP_MODE_LINEAR
}

/** Maps a portable blend factor to its Vulkan blend factor. */
internal fun BlendFactor.toVkBlendFactor(): UInt = when (this) {
    BlendFactor.Zero -> VK_BLEND_FACTOR_ZERO
    BlendFactor.One -> VK_BLEND_FACTOR_ONE
    BlendFactor.SrcColor -> VK_BLEND_FACTOR_SRC_COLOR
    BlendFactor.OneMinusSrcColor -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR
    BlendFactor.SrcAlpha -> VK_BLEND_FACTOR_SRC_ALPHA
    BlendFactor.OneMinusSrcAlpha -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
    BlendFactor.DstColor -> VK_BLEND_FACTOR_DST_COLOR
    BlendFactor.OneMinusDstColor -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR
    BlendFactor.DstAlpha -> VK_BLEND_FACTOR_DST_ALPHA
    BlendFactor.OneMinusDstAlpha -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA
    BlendFactor.ConstantColor -> VK_BLEND_FACTOR_CONSTANT_COLOR
    BlendFactor.OneMinusConstantColor -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR
    BlendFactor.SrcAlphaSaturated -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE
}

/** Maps a portable blend operation to its Vulkan blend operation. */
internal fun BlendOperation.toVkBlendOp(): UInt = when (this) {
    BlendOperation.Add -> VK_BLEND_OP_ADD
    BlendOperation.Subtract -> VK_BLEND_OP_SUBTRACT
    BlendOperation.ReverseSubtract -> VK_BLEND_OP_REVERSE_SUBTRACT
    BlendOperation.Min -> VK_BLEND_OP_MIN
    BlendOperation.Max -> VK_BLEND_OP_MAX
}

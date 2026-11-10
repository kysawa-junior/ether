package dev.instrum.ether.render.lwjgl.internal

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
import org.lwjgl.vulkan.VK10

/** Maps a portable texture format to its Vulkan format. */
internal fun TextureFormat.toVkFormat(): Int = when (this) {
    TextureFormat.R8Unorm -> VK10.VK_FORMAT_R8_UNORM
    TextureFormat.Rg8Unorm -> VK10.VK_FORMAT_R8G8_UNORM
    TextureFormat.Rgba8Unorm -> VK10.VK_FORMAT_R8G8B8A8_UNORM
    TextureFormat.Rgba8Srgb -> VK10.VK_FORMAT_R8G8B8A8_SRGB
    TextureFormat.Bgra8Unorm -> VK10.VK_FORMAT_B8G8R8A8_UNORM
    TextureFormat.Bgra8Srgb -> VK10.VK_FORMAT_B8G8R8A8_SRGB
    TextureFormat.Rgba16Float -> VK10.VK_FORMAT_R16G16B16A16_SFLOAT
    TextureFormat.Rgba32Float -> VK10.VK_FORMAT_R32G32B32A32_SFLOAT
    TextureFormat.Depth16Unorm -> VK10.VK_FORMAT_D16_UNORM
    TextureFormat.Depth24Plus -> VK10.VK_FORMAT_X8_D24_UNORM_PACK32
    TextureFormat.Depth24PlusStencil8 -> VK10.VK_FORMAT_D24_UNORM_S8_UINT
    TextureFormat.Depth32Float -> VK10.VK_FORMAT_D32_SFLOAT
}

/** Maps a portable vertex format to its Vulkan vertex format. */
internal fun VertexFormat.toVkFormat(): Int = when (this) {
    VertexFormat.Float32 -> VK10.VK_FORMAT_R32_SFLOAT
    VertexFormat.Float32x2 -> VK10.VK_FORMAT_R32G32_SFLOAT
    VertexFormat.Float32x3 -> VK10.VK_FORMAT_R32G32B32_SFLOAT
    VertexFormat.Float32x4 -> VK10.VK_FORMAT_R32G32B32A32_SFLOAT
    VertexFormat.UInt8x4 -> VK10.VK_FORMAT_R8G8B8A8_UINT
    VertexFormat.UInt16x2 -> VK10.VK_FORMAT_R16G16_UINT
    VertexFormat.UInt16x4 -> VK10.VK_FORMAT_R16G16B16A16_UINT
    VertexFormat.UInt32 -> VK10.VK_FORMAT_R32_UINT
}

/** Maps a portable index format to its Vulkan index type. */
internal fun IndexFormat.toVkIndexType(): Int = when (this) {
    IndexFormat.UInt16 -> VK10.VK_INDEX_TYPE_UINT16
    IndexFormat.UInt32 -> VK10.VK_INDEX_TYPE_UINT32
}

/** Maps a portable topology to its Vulkan primitive topology. */
internal fun PrimitiveTopology.toVkTopology(): Int = when (this) {
    PrimitiveTopology.TriangleList -> VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
    PrimitiveTopology.TriangleStrip -> VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP
    PrimitiveTopology.LineList -> VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST
    PrimitiveTopology.LineStrip -> VK10.VK_PRIMITIVE_TOPOLOGY_LINE_STRIP
    PrimitiveTopology.PointList -> VK10.VK_PRIMITIVE_TOPOLOGY_POINT_LIST
}

/** Maps a portable cull mode to its Vulkan cull mode flags. */
internal fun CullMode.toVkCullMode(): Int = when (this) {
    CullMode.None -> VK10.VK_CULL_MODE_NONE
    CullMode.Front -> VK10.VK_CULL_MODE_FRONT_BIT
    CullMode.Back -> VK10.VK_CULL_MODE_BACK_BIT
}

/** Maps a portable front face to its Vulkan front face constant. */
internal fun FrontFace.toVkFrontFace(): Int = when (this) {
    FrontFace.CounterClockwise -> VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE
    FrontFace.Clockwise -> VK10.VK_FRONT_FACE_CLOCKWISE
}

/** Maps a portable compare function to its Vulkan compare operation. */
internal fun CompareFunction.toVkCompareOp(): Int = when (this) {
    CompareFunction.Never -> VK10.VK_COMPARE_OP_NEVER
    CompareFunction.Less -> VK10.VK_COMPARE_OP_LESS
    CompareFunction.Equal -> VK10.VK_COMPARE_OP_EQUAL
    CompareFunction.LessEqual -> VK10.VK_COMPARE_OP_LESS_OR_EQUAL
    CompareFunction.Greater -> VK10.VK_COMPARE_OP_GREATER
    CompareFunction.NotEqual -> VK10.VK_COMPARE_OP_NOT_EQUAL
    CompareFunction.GreaterEqual -> VK10.VK_COMPARE_OP_GREATER_OR_EQUAL
    CompareFunction.Always -> VK10.VK_COMPARE_OP_ALWAYS
}

/** Maps a portable address mode to its Vulkan sampler address mode. */
internal fun AddressMode.toVkAddressMode(): Int = when (this) {
    AddressMode.ClampToEdge -> VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
    AddressMode.Repeat -> VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT
    AddressMode.MirrorRepeat -> VK10.VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT
}

/** Maps a portable filter mode to its Vulkan filter. */
internal fun FilterMode.toVkFilter(): Int = when (this) {
    FilterMode.Nearest -> VK10.VK_FILTER_NEAREST
    FilterMode.Linear -> VK10.VK_FILTER_LINEAR
}

/** Maps a portable mipmap filter to its Vulkan sampler mipmap mode. */
internal fun MipmapFilterMode.toVkMipmapMode(): Int = when (this) {
    MipmapFilterMode.Nearest -> VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST
    MipmapFilterMode.Linear -> VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR
}

/** Maps a portable blend factor to its Vulkan blend factor. */
internal fun BlendFactor.toVkBlendFactor(): Int = when (this) {
    BlendFactor.Zero -> VK10.VK_BLEND_FACTOR_ZERO
    BlendFactor.One -> VK10.VK_BLEND_FACTOR_ONE
    BlendFactor.SrcColor -> VK10.VK_BLEND_FACTOR_SRC_COLOR
    BlendFactor.OneMinusSrcColor -> VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR
    BlendFactor.SrcAlpha -> VK10.VK_BLEND_FACTOR_SRC_ALPHA
    BlendFactor.OneMinusSrcAlpha -> VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
    BlendFactor.DstColor -> VK10.VK_BLEND_FACTOR_DST_COLOR
    BlendFactor.OneMinusDstColor -> VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR
    BlendFactor.DstAlpha -> VK10.VK_BLEND_FACTOR_DST_ALPHA
    BlendFactor.OneMinusDstAlpha -> VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA
    BlendFactor.ConstantColor -> VK10.VK_BLEND_FACTOR_CONSTANT_COLOR
    BlendFactor.OneMinusConstantColor -> VK10.VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR
    BlendFactor.SrcAlphaSaturated -> VK10.VK_BLEND_FACTOR_SRC_ALPHA_SATURATE
}

/** Maps a portable blend operation to its Vulkan blend operation. */
internal fun BlendOperation.toVkBlendOp(): Int = when (this) {
    BlendOperation.Add -> VK10.VK_BLEND_OP_ADD
    BlendOperation.Subtract -> VK10.VK_BLEND_OP_SUBTRACT
    BlendOperation.ReverseSubtract -> VK10.VK_BLEND_OP_REVERSE_SUBTRACT
    BlendOperation.Min -> VK10.VK_BLEND_OP_MIN
    BlendOperation.Max -> VK10.VK_BLEND_OP_MAX
}

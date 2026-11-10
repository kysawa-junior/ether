package dev.instrum.ether.render.api

import kotlin.jvm.JvmInline

/**
 * Component format of a vertex attribute.
 *
 * @property componentCount number of scalar components.
 * @property byteSize size of one attribute value in bytes.
 */
public enum class VertexFormat(
    public val componentCount: Int,
    public val byteSize: Int,
) {
    /** One 32-bit float. */
    Float32(1, 4),

    /** Two 32-bit floats. */
    Float32x2(2, 8),

    /** Three 32-bit floats. */
    Float32x3(3, 12),

    /** Four 32-bit floats. */
    Float32x4(4, 16),

    /** Four 8-bit unsigned integers. */
    UInt8x4(4, 4),

    /** Two 16-bit unsigned integers. */
    UInt16x2(2, 4),

    /** Four 16-bit unsigned integers. */
    UInt16x4(4, 8),

    /** One 32-bit unsigned integer. */
    UInt32(1, 4),
}

/**
 * Whether a vertex buffer advances per vertex or per instance.
 */
public enum class VertexStepMode {
    /** Advance once per vertex. */
    Vertex,

    /** Advance once per instance. */
    Instance,
}

/**
 * One attribute inside a vertex buffer layout.
 *
 * @property location shader input location; must not be negative.
 * @property format component format.
 * @property offsetBytes byte offset inside the vertex stride.
 */
public data class VertexAttribute(
    public val location: Int,
    public val format: VertexFormat,
    public val offsetBytes: Int,
) {
    init {
        require(location >= 0) { "Vertex attribute location must not be negative" }
        require(offsetBytes >= 0) { "Vertex attribute offset must not be negative" }
    }
}

/**
 * Layout of one vertex buffer binding.
 *
 * @property strideBytes distance between consecutive elements; must be positive.
 * @property stepMode whether the buffer advances per vertex or per instance.
 * @property attributes non-empty list of attributes with unique locations.
 */
public data class VertexBufferLayout(
    public val strideBytes: Int,
    public val stepMode: VertexStepMode = VertexStepMode.Vertex,
    public val attributes: List<VertexAttribute>,
) {
    init {
        require(strideBytes > 0) { "Vertex stride must be positive" }
        require(attributes.isNotEmpty()) { "Vertex buffer layout must declare at least one attribute" }
        val locations = attributes.map { it.location }
        require(locations.size == locations.toSet().size) {
            "Vertex attribute locations must be unique within a buffer layout"
        }
        attributes.forEach { attribute ->
            require(attribute.offsetBytes + attribute.format.byteSize <= strideBytes) {
                "Vertex attribute at location ${attribute.location} overflows stride $strideBytes"
            }
        }
    }
}

/**
 * Primitive topology for rasterization.
 */
public enum class PrimitiveTopology {
    /** Independent triangles. */
    TriangleList,

    /** Triangle strip. */
    TriangleStrip,

    /** Independent lines. */
    LineList,

    /** Line strip. */
    LineStrip,

    /** Points. */
    PointList,
}

/**
 * Face culling mode.
 */
public enum class CullMode {
    /** Draw both faces. */
    None,

    /** Cull front faces. */
    Front,

    /** Cull back faces. */
    Back,
}

/**
 * Winding order that identifies a front face.
 */
public enum class FrontFace {
    /** Counter-clockwise winding is front-facing. */
    CounterClockwise,

    /** Clockwise winding is front-facing. */
    Clockwise,
}

/**
 * Depth or stencil comparison function.
 */
public enum class CompareFunction {
    /** Never pass. */
    Never,

    /** Pass when the new value is less than the stored value. */
    Less,

    /** Pass when values are equal. */
    Equal,

    /** Pass when the new value is less than or equal. */
    LessEqual,

    /** Pass when the new value is greater. */
    Greater,

    /** Pass when values are not equal. */
    NotEqual,

    /** Pass when the new value is greater than or equal. */
    GreaterEqual,

    /** Always pass. */
    Always,
}

/**
 * Index component format.
 *
 * @property byteSize size of one index in bytes.
 */
public enum class IndexFormat(public val byteSize: Int) {
    /** 16-bit unsigned indices. */
    UInt16(2),

    /** 32-bit unsigned indices. */
    UInt32(4),
}

/**
 * Color channel used by [ColorWriteMask].
 */
public enum class ColorChannel(public val bit: Int) {
    /** Red channel. */
    Red(0x1),

    /** Green channel. */
    Green(0x2),

    /** Blue channel. */
    Blue(0x4),

    /** Alpha channel. */
    Alpha(0x8),
}

/**
 * Bitmask selecting which color channels a pipeline writes.
 */
@JvmInline
public value class ColorWriteMask(public val bits: Int) {
    init {
        require(bits and 0xF.inv() == 0) { "Color write mask may only use the low four bits" }
    }

    /** Whether [channel] is written. */
    public fun contains(channel: ColorChannel): Boolean = (bits and channel.bit) != 0

    public companion object {
        /** Write no channels. */
        public val None: ColorWriteMask = ColorWriteMask(0)

        /** Write the red channel. */
        public val Red: ColorWriteMask = ColorWriteMask(ColorChannel.Red.bit)

        /** Write the green channel. */
        public val Green: ColorWriteMask = ColorWriteMask(ColorChannel.Green.bit)

        /** Write the blue channel. */
        public val Blue: ColorWriteMask = ColorWriteMask(ColorChannel.Blue.bit)

        /** Write the alpha channel. */
        public val Alpha: ColorWriteMask = ColorWriteMask(ColorChannel.Alpha.bit)

        /** Write every channel. */
        public val All: ColorWriteMask = ColorWriteMask(0xF)

        /** Builds a mask from the given channels. */
        public fun of(vararg channels: ColorChannel): ColorWriteMask {
            var bits = 0
            channels.forEach { bits = bits or it.bit }
            return ColorWriteMask(bits)
        }
    }
}

/**
 * Blend factor.
 */
public enum class BlendFactor {
    /** Zero. */
    Zero,

    /** One. */
    One,

    /** Source color. */
    SrcColor,

    /** One minus source color. */
    OneMinusSrcColor,

    /** Source alpha. */
    SrcAlpha,

    /** One minus source alpha. */
    OneMinusSrcAlpha,

    /** Destination color. */
    DstColor,

    /** One minus destination color. */
    OneMinusDstColor,

    /** Destination alpha. */
    DstAlpha,

    /** One minus destination alpha. */
    OneMinusDstAlpha,

    /** Constant blend color. */
    ConstantColor,

    /** One minus constant blend color. */
    OneMinusConstantColor,

    /** Source alpha, saturated. */
    SrcAlphaSaturated,
}

/**
 * Blend equation.
 */
public enum class BlendOperation {
    /** Source + destination. */
    Add,

    /** Source - destination. */
    Subtract,

    /** Destination - source. */
    ReverseSubtract,

    /** Minimum of source and destination. */
    Min,

    /** Maximum of source and destination. */
    Max,
}

/**
 * Blend state for one color/alpha component.
 */
public data class BlendComponent(
    public val srcFactor: BlendFactor = BlendFactor.One,
    public val dstFactor: BlendFactor = BlendFactor.Zero,
    public val operation: BlendOperation = BlendOperation.Add,
)

/**
 * Color and alpha blending state.
 */
public data class BlendState(
    public val color: BlendComponent = BlendComponent(),
    public val alpha: BlendComponent = BlendComponent(),
)

/**
 * Describes one color target of a render pipeline.
 *
 * @property format the attachment format; must not be a depth format.
 * @property blend optional blending state; `null` disables blending.
 * @property writeMask channels written by the pipeline.
 */
public data class ColorTargetState(
    public val format: TextureFormat,
    public val blend: BlendState? = null,
    public val writeMask: ColorWriteMask = ColorWriteMask.All,
) {
    init {
        require(!format.isDepthStencil) { "Color targets cannot use depth format $format" }
    }
}

/**
 * Multisample state.
 *
 * @property count samples per pixel; a power of two between 1 and 64.
 * @property alphaToCoverageEnabled whether alpha is converted to coverage.
 */
public data class MultisampleState(
    public val count: Int = 1,
    public val alphaToCoverageEnabled: Boolean = false,
) {
    init {
        require(count > 0) { "Multisample count must be positive" }
        require(count <= 64) { "Multisample count must not exceed 64" }
        require(count == 1 || (count and (count - 1)) == 0) {
            "Multisample count must be a power of two, was $count"
        }
        require(!alphaToCoverageEnabled || count > 1) {
            "Alpha-to-coverage requires multisampling"
        }
    }
}

/**
 * Depth test and write state.
 *
 * @property format the depth attachment format; must be a depth format.
 * @property depthWriteEnabled whether depth values are written.
 * @property depthCompare depth comparison function.
 */
public data class DepthStencilState(
    public val format: TextureFormat,
    public val depthWriteEnabled: Boolean = true,
    public val depthCompare: CompareFunction = CompareFunction.Less,
) {
    init {
        require(format.isDepthStencil) { "Depth state requires a depth format, got $format" }
    }
}

/**
 * Rasterization state.
 */
public data class PrimitiveState(
    public val topology: PrimitiveTopology = PrimitiveTopology.TriangleList,
    public val cullMode: CullMode = CullMode.None,
    public val frontFace: FrontFace = FrontFace.CounterClockwise,
    public val stripIndexFormat: IndexFormat? = null,
    public val unclippedDepth: Boolean = false,
) {
    init {
        val isStrip = topology == PrimitiveTopology.TriangleStrip ||
            topology == PrimitiveTopology.LineStrip
        require(!isStrip || stripIndexFormat != null) {
            "Strip topologies require stripIndexFormat"
        }
        require(isStrip || stripIndexFormat == null) {
            "stripIndexFormat is only valid for strip topologies"
        }
    }
}

/**
 * Vertex shader and vertex buffer layout state.
 */
public data class VertexState(
    public val shader: ShaderModuleHandle,
    public val buffers: List<VertexBufferLayout> = emptyList(),
)

/**
 * Fragment shader and color target state.
 */
public data class FragmentState(
    public val shader: ShaderModuleHandle,
    public val targets: List<ColorTargetState>,
)

/**
 * Describes a render pipeline.
 *
 * @property label an optional debug label.
 * @property vertex vertex stage state.
 * @property fragment optional fragment stage state. A pipeline with no fragment stage must write depth.
 * @property primitive rasterization state.
 * @property depthStencil optional depth state.
 * @property multisample multisample state.
 */
public data class RenderPipelineDescriptor(
    public val label: String? = null,
    public val vertex: VertexState,
    public val fragment: FragmentState? = null,
    public val primitive: PrimitiveState = PrimitiveState(),
    public val depthStencil: DepthStencilState? = null,
    public val multisample: MultisampleState = MultisampleState(),
) {
    init {
        val allLocations = vertex.buffers.flatMap { layout -> layout.attributes.map { it.location } }
        require(allLocations.size == allLocations.toSet().size) {
            "Vertex attribute locations must be unique across all vertex buffers"
        }
        require(fragment != null || depthStencil != null) {
            "A render pipeline must have a fragment stage or depth state"
        }
    }
}

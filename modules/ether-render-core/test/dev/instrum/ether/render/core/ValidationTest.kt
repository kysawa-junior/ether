package dev.instrum.ether.render.core

import dev.instrum.ether.render.api.BufferDescriptor
import dev.instrum.ether.render.api.BufferUsage
import dev.instrum.ether.render.api.ColorTargetState
import dev.instrum.ether.render.api.DepthStencilState
import dev.instrum.ether.render.api.FragmentState
import dev.instrum.ether.render.api.GraphicsFeature
import dev.instrum.ether.render.api.GraphicsLimits
import dev.instrum.ether.render.api.RenderPipelineDescriptor
import dev.instrum.ether.render.api.SamplerDescriptor
import dev.instrum.ether.render.api.ShaderModuleDescriptor
import dev.instrum.ether.render.api.ShaderStage
import dev.instrum.ether.render.api.TextureDescriptor
import dev.instrum.ether.render.api.TextureFormat
import dev.instrum.ether.render.api.TextureUsage
import dev.instrum.ether.render.api.VertexAttribute
import dev.instrum.ether.render.api.VertexBufferLayout
import dev.instrum.ether.render.api.VertexFormat
import dev.instrum.ether.render.api.VertexState
import dev.instrum.ether.render.core.error.RenderValidationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ValidationTest {
    @Test
    fun bufferSizeIsLimited() {
        val fixture = createNullTestDevice(
            capabilities = testCapabilities(limits = GraphicsLimits.Permissive.copy(maxBufferSize = 64L)),
        )
        val device = fixture.device
        try {
            assertFailsWith<RenderValidationException> {
                device.createBuffer(
                    BufferDescriptor(65L, setOf(BufferUsage.Vertex)),
                )
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun storageBuffersRequireFeature() {
        val fixture = createNullTestDevice(
            capabilities = testCapabilities(features = emptySet()),
        )
        val device = fixture.device
        try {
            assertFailsWith<RenderValidationException> {
                device.createBuffer(BufferDescriptor(16L, setOf(BufferUsage.Storage)))
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun uniformBuffersMustFitBindingLimit() {
        val fixture = createNullTestDevice(
            capabilities = testCapabilities(
                limits = GraphicsLimits.Permissive.copy(maxUniformBufferBindingSize = 64L),
            ),
        )
        val device = fixture.device
        try {
            assertFailsWith<RenderValidationException> {
                device.createBuffer(BufferDescriptor(65L, setOf(BufferUsage.Uniform)))
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun textureFormatMustBeSupported() {
        val fixture = createNullTestDevice(
            capabilities = testCapabilities(formats = setOf(TextureFormat.Rgba8Unorm)),
        )
        val device = fixture.device
        try {
            assertFailsWith<RenderValidationException> {
                device.createTexture(
                    TextureDescriptor(
                        width = 8,
                        height = 8,
                        format = TextureFormat.Bgra8Unorm,
                        usage = setOf(TextureUsage.Sampled),
                    ),
                )
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun textureDimensionsRespectLimits() {
        val fixture = createNullTestDevice(
            capabilities = testCapabilities(
                limits = GraphicsLimits.Permissive.copy(maxTexture2DSize = 64),
            ),
        )
        val device = fixture.device
        try {
            assertFailsWith<RenderValidationException> {
                device.createTexture(
                    TextureDescriptor(
                        width = 128,
                        height = 64,
                        format = TextureFormat.Rgba8Unorm,
                        usage = setOf(TextureUsage.Sampled),
                    ),
                )
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun textureSampleCountRespectsLimits() {
        val fixture = createNullTestDevice(
            capabilities = testCapabilities(
                limits = GraphicsLimits.Permissive.copy(maxSampleCount = 2),
            ),
        )
        val device = fixture.device
        try {
            assertFailsWith<RenderValidationException> {
                device.createTexture(
                    TextureDescriptor(
                        width = 8,
                        height = 8,
                        sampleCount = 4,
                        format = TextureFormat.Rgba8Unorm,
                        usage = setOf(TextureUsage.RenderTarget),
                    ),
                )
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun storageTexturesRequireFeature() {
        val fixture = createNullTestDevice(
            capabilities = testCapabilities(features = emptySet()),
        )
        val device = fixture.device
        try {
            assertFailsWith<RenderValidationException> {
                device.createTexture(
                    TextureDescriptor(
                        width = 8,
                        height = 8,
                        format = TextureFormat.Rgba8Unorm,
                        usage = setOf(TextureUsage.Storage),
                    ),
                )
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun anisotropicSamplersRequireFeatureAndLimit() {
        val noAnisotropy = createNullTestDevice(
            capabilities = testCapabilities(features = emptySet()),
        )
        try {
            assertFailsWith<RenderValidationException> {
                noAnisotropy.device.createSampler(SamplerDescriptor(maxAnisotropy = 4))
            }
        } finally {
            noAnisotropy.device.close()
        }

        val smallLimit = createNullTestDevice(
            capabilities = testCapabilities(
                limits = GraphicsLimits.Permissive.copy(maxSamplerAnisotropy = 2),
            ),
        )
        try {
            assertFailsWith<RenderValidationException> {
                smallLimit.device.createSampler(SamplerDescriptor(maxAnisotropy = 4))
            }
        } finally {
            smallLimit.device.close()
        }
    }

    @Test
    fun computeShadersRequireFeature() {
        val fixture = createNullTestDevice(
            capabilities = testCapabilities(features = emptySet()),
        )
        val device = fixture.device
        try {
            assertFailsWith<RenderValidationException> {
                device.createShaderModule(
                    ShaderModuleDescriptor(byteArrayOf(1), ShaderStage.Compute),
                )
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun pipelineShaderStagesMustMatch() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val fragment = device.createTestShader(ShaderStage.Fragment)
            val vertex = device.createTestShader(ShaderStage.Vertex)
            assertFailsWith<RenderValidationException> {
                device.createRenderPipeline(
                    RenderPipelineDescriptor(
                        vertex = VertexState(fragment),
                        fragment = FragmentState(
                            vertex,
                            listOf(ColorTargetState(TextureFormat.Rgba8Unorm)),
                        ),
                    ),
                )
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun pipelineVertexBufferCountRespectsLimit() {
        val fixture = createNullTestDevice(
            capabilities = testCapabilities(
                limits = GraphicsLimits.Permissive.copy(maxVertexBuffers = 1),
            ),
        )
        val device = fixture.device
        try {
            val vertex = device.createTestShader(ShaderStage.Vertex)
            val fragment = device.createTestShader(ShaderStage.Fragment)
            val layoutA = VertexBufferLayout(
                strideBytes = 4,
                attributes = listOf(VertexAttribute(0, VertexFormat.Float32, 0)),
            )
            val layoutB = VertexBufferLayout(
                strideBytes = 4,
                attributes = listOf(VertexAttribute(1, VertexFormat.Float32, 0)),
            )
            assertFailsWith<RenderValidationException> {
                device.createRenderPipeline(
                    RenderPipelineDescriptor(
                        vertex = VertexState(vertex, listOf(layoutA, layoutB)),
                        fragment = FragmentState(
                            fragment,
                            listOf(ColorTargetState(TextureFormat.Rgba8Unorm)),
                        ),
                    ),
                )
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun pipelineColorTargetCountRespectsLimit() {
        val fixture = createNullTestDevice(
            capabilities = testCapabilities(
                limits = GraphicsLimits.Permissive.copy(maxColorAttachments = 1),
            ),
        )
        val device = fixture.device
        try {
            assertFailsWith<RenderValidationException> {
                device.createTestPipeline(colorTargetCount = 2)
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun pipelineDepthFormatMustBeSupported() {
        val fixture = createNullTestDevice(
            capabilities = testCapabilities(formats = setOf(TextureFormat.Rgba8Unorm)),
        )
        val device = fixture.device
        try {
            assertFailsWith<RenderValidationException> {
                device.createTestPipeline(depthFormat = TextureFormat.Depth24Plus)
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun unclippedDepthRequiresFeature() {
        val fixture = createNullTestDevice(
            capabilities = testCapabilities(features = emptySet()),
        )
        val device = fixture.device
        try {
            val vertex = device.createTestShader(ShaderStage.Vertex)
            val fragment = device.createTestShader(ShaderStage.Fragment)
            assertFailsWith<RenderValidationException> {
                device.createRenderPipeline(
                    RenderPipelineDescriptor(
                        vertex = VertexState(vertex),
                        fragment = FragmentState(
                            fragment,
                            listOf(ColorTargetState(TextureFormat.Rgba8Unorm)),
                        ),
                        primitive = dev.instrum.ether.render.api.PrimitiveState(unclippedDepth = true),
                    ),
                )
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun pipelineMustWriteSomething() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val vertex = device.createTestShader(ShaderStage.Vertex)
            val fragment = device.createTestShader(ShaderStage.Fragment)
            assertFailsWith<RenderValidationException> {
                device.createRenderPipeline(
                    RenderPipelineDescriptor(
                        vertex = VertexState(vertex),
                        fragment = FragmentState(fragment, emptyList()),
                    ),
                )
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun depthStencilStateSmoke() {
        // Guards against accidental removal of the state type from the public API.
        val state = DepthStencilState(TextureFormat.Depth24Plus)
        kotlin.test.assertTrue(state.depthWriteEnabled)
    }
}

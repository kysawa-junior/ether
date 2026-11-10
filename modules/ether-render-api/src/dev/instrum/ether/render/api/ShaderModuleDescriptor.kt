package dev.instrum.ether.render.api

/**
 * Shader pipeline stage.
 */
public enum class ShaderStage {
    /** Vertex shader. */
    Vertex,

    /** Fragment shader. */
    Fragment,

    /** Compute shader. */
    Compute,
}

/**
 * Describes a shader module.
 *
 * The code format is defined by the backend: SPIR-V for Vulkan, GLSL ES 3.00 source for WebGL2,
 * and MSL source for Metal. The core validates metadata but does not parse shader code.
 *
 * @property code the shader source or binary; copied defensively on construction.
 * @property stage the pipeline stage this module implements.
 * @property entryPoint the function executed for this stage.
 * @property label an optional debug label.
 */
public class ShaderModuleDescriptor(
    code: ByteArray,
    public val stage: ShaderStage,
    public val entryPoint: String = "main",
    public val label: String? = null,
) {
    /** A defensive copy of the shader code. Callers must not rely on identity. */
    public val code: ByteArray = code.copyOf()

    init {
        require(this.code.isNotEmpty()) { "Shader code must not be empty" }
        require(entryPoint.isNotBlank()) { "Shader entry point must not be blank" }
    }
}

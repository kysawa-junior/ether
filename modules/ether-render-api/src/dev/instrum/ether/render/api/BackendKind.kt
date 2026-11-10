package dev.instrum.ether.render.api

/**
 * Identifies a rendering backend implementation.
 */
public enum class BackendKind {
    /** Headless backend that validates and records commands without executing GPU work. */
    Null,

    /** Vulkan 1.3 backend. */
    Vulkan,

    /** Metal backend. */
    Metal,

    /** WebGL2 backend. */
    WebGl2,

    /** Browser WebGPU backend. Optional. */
    WebGpu,
}

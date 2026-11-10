package dev.instrum.ether.render.lwjgl

/**
 * Identifies the physical device a Vulkan device was created on.
 *
 * @property physicalDeviceName the driver reported device name.
 * @property apiVersion the Vulkan version reported by the device, formatted as `major.minor.patch`.
 * @property driverVersion the driver specific version number.
 * @property vendorId the PCI vendor identifier.
 * @property deviceType the human readable device class, for example `DISCRETE_GPU`.
 * @property queueFamilyIndex the index of the graphics queue family used by the device.
 * @property queueCount the number of queues in the graphics queue family.
 */
public data class LwjglDeviceInfo(
    public val physicalDeviceName: String,
    public val apiVersion: String,
    public val driverVersion: Int,
    public val vendorId: Int,
    public val deviceType: String,
    public val queueFamilyIndex: Int,
    public val queueCount: Int,
)

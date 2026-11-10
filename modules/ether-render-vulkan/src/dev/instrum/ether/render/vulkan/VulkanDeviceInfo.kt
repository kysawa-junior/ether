package dev.instrum.ether.render.vulkan

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
public data class VulkanDeviceInfo(
    public val physicalDeviceName: String,
    public val apiVersion: String,
    public val driverVersion: UInt,
    public val vendorId: UInt,
    public val deviceType: String,
    public val queueFamilyIndex: UInt,
    public val queueCount: UInt,
)

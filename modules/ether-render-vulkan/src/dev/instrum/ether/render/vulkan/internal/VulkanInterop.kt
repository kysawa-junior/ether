@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.instrum.ether.render.vulkan.internal

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CVariable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.NativePlacement
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf

/**
 * Allocates a zero initialized native variable.
 *
 * Vulkan reads every field of a create-info structure, so uninitialized memory is never acceptable.
 * Kotlin/Native's [alloc] uses `malloc` and therefore returns dirty memory.
 */
internal inline fun <reified T : CVariable> NativePlacement.zeroed(): T {
    val variable = alloc<T>()
    zeroBytes(variable.ptr.reinterpret<ByteVar>(), sizeOf<T>().toInt())
    return variable
}

/**
 * Allocates a zero initialized native array.
 *
 * @param length number of elements; must be positive.
 */
internal inline fun <reified T : CVariable> NativePlacement.zeroedArray(length: Int): CPointer<T> {
    require(length > 0) { "Native array length must be positive, was $length" }
    val array = allocArray<T>(length)
    zeroBytes(array.reinterpret<ByteVar>(), sizeOf<T>().toInt() * length)
    return array
}

/**
 * Allocates a NUL terminated copy of [value] in the current scope.
 *
 * The buffer stays valid until the enclosing scope ends, so it can be handed to a Vulkan create
 * call that stores the pointer.
 */
internal fun NativePlacement.nativeString(value: String): CPointer<ByteVar> {
    val bytes = value.encodeToByteArray()
    val buffer = allocArray<ByteVar>(bytes.size + 1)
    bytes.forEachIndexed { index, byte -> buffer[index] = byte }
    buffer[bytes.size] = 0
    return buffer
}

/**
 * Writes zero into the first [count] bytes of [target].
 */
private fun zeroBytes(target: CPointer<ByteVar>, count: Int) {
    for (index in 0 until count) {
        target[index] = 0
    }
}

/**
 * Copies [data] into native memory.
 *
 * @param offset the byte offset inside the mapped region.
 */
internal fun CPointer<ByteVar>.writeBytes(data: ByteArray, offset: Int = 0) {
    for (index in data.indices) {
        this[offset + index] = data[index]
    }
}

/**
 * Reads [count] bytes from native memory.
 *
 * @param offset the byte offset inside the mapped region.
 */
internal fun CPointer<ByteVar>.readBytes(count: Int, offset: Int = 0): ByteArray {
    val result = ByteArray(count)
    for (index in 0 until count) {
        result[index] = this[offset + index]
    }
    return result
}

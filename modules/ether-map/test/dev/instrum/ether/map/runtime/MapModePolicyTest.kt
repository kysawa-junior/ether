package dev.instrum.ether.map.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** The user configurable swap policy: approved defaults (220 m / 420 m) and validation. */
class MapModePolicyTest {

    @Test
    fun `politica padrao usa 220 e 420 metros`() {
        assertSame(MapModePolicy.Defaults, MapModePolicy.Defaults, "padrão é único")
        assertEquals(220f, MapModePolicy.Defaults.closeThresholdMeters)
        assertEquals(420f, MapModePolicy.Defaults.farThresholdMeters)
    }

    @Test
    fun `politica rejeita limiares invalidos`() {
        assertFailsWith<IllegalArgumentException> { MapModePolicy(0f, 420f) }
        assertFailsWith<IllegalArgumentException> { MapModePolicy(-1f, 420f) }
        assertFailsWith<IllegalArgumentException> { MapModePolicy(420f, 220f) }
        assertFailsWith<IllegalArgumentException> { MapModePolicy(300f, 300f) }
    }
}

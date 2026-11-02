package org.autojs.autoxjs.mcp

import org.autojs.plugin.api.PluginConfigHost
import org.autojs.plugin.api.PluginConfigListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空闲休眠配置的解析与边界收敛。
 *
 * 这两个值会直接影响「服务会不会自己停掉」，一旦解析错（比如 0 分钟）
 * 就会导致服务刚启动就休眠、AI 永远连不上，因此单独覆盖。
 */
class McpPrefsTest {

    private class FakeConfig(private val values: Map<String, Any> = emptyMap()) : PluginConfigHost {
        override fun getString(key: String, defaultValue: String?): String? =
            values[key] as? String ?: defaultValue

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            values[key] as? Boolean ?: defaultValue

        override fun getInt(key: String, defaultValue: Int): Int =
            values[key] as? Int ?: defaultValue

        override fun putString(key: String, value: String?) = Unit
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun putInt(key: String, value: Int) = Unit
        override fun registerListener(listener: PluginConfigListener) = Unit
        override fun unregisterListener(listener: PluginConfigListener) = Unit
    }

    @Test
    fun idleDefaultsToEnabledThirtyMinutes() {
        val config = McpPrefs.load(FakeConfig())
        assertTrue("未配置时应默认开启休眠", config.idleShutdown)
        assertEquals(30, config.idleMinutes)
    }

    @Test
    fun idleMinutesIsClampedToConfiguredRange() {
        assertEquals(
            McpConfig.MIN_IDLE_MINUTES,
            McpPrefs.load(FakeConfig(mapOf(McpPrefKeys.KEY_IDLE_MINUTES to 1))).idleMinutes
        )
        assertEquals(
            McpConfig.MAX_IDLE_MINUTES,
            McpPrefs.load(FakeConfig(mapOf(McpPrefKeys.KEY_IDLE_MINUTES to 99_999))).idleMinutes
        )
        // 0 或负数是最危险的输入：不收敛就会「立刻休眠」。
        assertEquals(
            McpConfig.MIN_IDLE_MINUTES,
            McpPrefs.load(FakeConfig(mapOf(McpPrefKeys.KEY_IDLE_MINUTES to 0))).idleMinutes
        )
        assertEquals(
            McpConfig.MIN_IDLE_MINUTES,
            McpPrefs.load(FakeConfig(mapOf(McpPrefKeys.KEY_IDLE_MINUTES to -30))).idleMinutes
        )
    }

    @Test
    fun idleMinutesAcceptsValidValue() {
        assertEquals(
            120,
            McpPrefs.load(FakeConfig(mapOf(McpPrefKeys.KEY_IDLE_MINUTES to 120))).idleMinutes
        )
    }

    @Test
    fun idleShutdownCanBeTurnedOff() {
        val config = McpPrefs.load(FakeConfig(mapOf(McpPrefKeys.KEY_IDLE_SHUTDOWN to false)))
        assertFalse(config.idleShutdown)
    }
}

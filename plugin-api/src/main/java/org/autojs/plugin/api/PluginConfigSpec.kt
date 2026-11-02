package org.autojs.plugin.api

/**
 * 配置项类型，宿主据此决定用哪种控件渲染。
 */
enum class PluginConfigType {
    /** 布尔开关。 */
    BOOLEAN,

    /** 单行文本。 */
    TEXT,

    /** 整数，配合 [PluginConfigItem.min] / [PluginConfigItem.max] 做范围校验。 */
    NUMBER,

    /** 敏感文本（令牌、密钥），界面中以掩码显示。 */
    SECRET,

    /** 从 [PluginConfigItem.choices] 中单选。 */
    CHOICE
}

/**
 * [PluginConfigType.CHOICE] 的可选项。
 */
data class PluginConfigChoice(
    val value: String,
    val label: String
)

/**
 * 一个配置项的完整描述。
 *
 * 配置值统一以字符串形式承载，宿主按 [type] 做解析与校验；
 * 插件侧通过 [PluginHost.config] 读取时再按需转换。
 */
data class PluginConfigItem(
    val key: String,
    val type: PluginConfigType,
    val title: String,
    val summary: String? = null,
    val defaultValue: String? = null,
    /** 为 true 时宿主会拒绝保存空值。 */
    val required: Boolean = false,
    /** [PluginConfigType.NUMBER] 的下界（含）。 */
    val min: Int? = null,
    /** [PluginConfigType.NUMBER] 的上界（含）。 */
    val max: Int? = null,
    /** [PluginConfigType.CHOICE] 的候选项。 */
    val choices: List<PluginConfigChoice> = emptyList(),
    /** 输入框的空态提示（示例值），仅 TEXT / NUMBER / SECRET 使用。 */
    val placeholder: String? = null
)

/**
 * 配置分组，对应界面上的一个分类。`title` 为 null 时该组不带标题。
 */
data class PluginConfigSection(
    val title: String? = null,
    val items: List<PluginConfigItem>
)

/**
 * 插件向宿主声明的配置描述。
 *
 * 插件不需要自带任何界面：宿主读取本描述后用统一的偏好控件渲染设置页，
 * 值保存在宿主侧（按插件包名分区），插件通过 [PluginHost.config] 读写。
 * 这样配置的读写方与消费方始终在同一进程内，不存在跨进程同步问题。
 */
class PluginConfigSpec(val sections: List<PluginConfigSection> = emptyList()) {

    val isEmpty: Boolean
        get() = sections.all { it.items.isEmpty() }

    val items: List<PluginConfigItem>
        get() = sections.flatMap { it.items }

    fun item(key: String): PluginConfigItem? = items.firstOrNull { it.key == key }

    companion object {
        /** 插件未声明任何配置时使用。 */
        @JvmField
        val EMPTY = PluginConfigSpec()
    }
}

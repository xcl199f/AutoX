package org.autojs.autojs.plugin

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.autojs.autoxjs.R
import org.autojs.plugin.api.PluginConfigItem
import org.autojs.plugin.api.PluginConfigSpec
import org.autojs.plugin.api.PluginConfigType

/**
 * 插件配置界面：由宿主按插件声明的 [PluginConfigSpec] 动态渲染。
 *
 * 插件自身不提供任何 Activity 或布局，配置值也存放在宿主侧，
 * 因此用户在这里改动后，运行在宿主进程内的插件会立刻收到变更回调。
 */
class PluginConfigActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pluginPackage = intent.getStringExtra(EXTRA_PLUGIN_PACKAGE)
        if (pluginPackage.isNullOrBlank()) {
            finish()
            return
        }
        setContentView(R.layout.activity_plugin_config)
        applySystemBarAppearance()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState != null) {
            // 旋转等重建场景：Fragment 已由系统恢复，只需补回标题。
            supportActionBar?.title = PluginManager.loadedPlugin(pluginPackage)?.meta?.label
                ?: getString(R.string.plugin_config_title)
            return
        }

        // 加载插件涉及 dex 解析，放到 IO 线程；配置页本身很轻，加载完再挂 Fragment。
        scope.launch {
            val result = withContext(Dispatchers.IO) { PluginManager.ensureLoaded(pluginPackage) }
            val loaded = result.getOrElse { error ->
                Toast.makeText(
                    this@PluginConfigActivity,
                    getString(
                        R.string.plugin_config_load_failed,
                        error.message ?: error.javaClass.simpleName
                    ),
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }
            if (isFinishing || isDestroyed) {
                return@launch
            }
            supportActionBar?.title = loaded.meta.label
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, PluginConfigFragment.newInstance(pluginPackage))
                .commit()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PLUGIN_PACKAGE = "org.autojs.autojs.plugin.extra.PACKAGE"

        fun intent(context: Context, packageName: String): Intent =
            Intent(context, PluginConfigActivity::class.java)
                .putExtra(EXTRA_PLUGIN_PACKAGE, packageName)
    }
}

/**
 * 把 [PluginConfigSpec] 翻译成偏好界面。
 */
class PluginConfigFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val pluginPackage = requireArguments().getString(ARG_PLUGIN_PACKAGE) ?: return
        val rawSpec = PluginManager.configSpec(pluginPackage)

        val screen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceScreen = screen

        if (PluginManager.isPluginIncompatible(pluginPackage)) {
            screen.addPreference(
                Preference(requireContext()).apply {
                    isPersistent = false
                    isEnabled = false
                    title = getString(R.string.plugin_config_incompatible_title)
                    summary = getString(R.string.plugin_config_incompatible_summary)
                }
            )
            return
        }

        val spec = rawSpec ?: PluginConfigSpec.EMPTY
        val store = PluginManager.configStore(pluginPackage)

        if (spec.isEmpty) {
            screen.addPreference(
                Preference(requireContext()).apply {
                    isPersistent = false
                    isEnabled = false
                    title = getString(R.string.plugin_config_empty)
                }
            )
            addLegacySettingsEntry(screen, pluginPackage)
            return
        }

        spec.sections.forEach { section ->
            val category = PreferenceCategory(requireContext()).apply {
                isPersistent = false
                title = section.title
            }
            screen.addPreference(category)
            section.items.forEach { item -> category.addPreference(buildPreference(item, store)) }
        }
        addLegacySettingsEntry(screen, pluginPackage)
    }

    /**
     * 兼容尚未迁移到 schema 的旧插件：它自带设置页时给一个入口。
     */
    private fun addLegacySettingsEntry(screen: PreferenceGroup, pluginPackage: String) {
        val component = PluginManager.loadedPlugin(pluginPackage)?.meta?.settingsComponent ?: return
        screen.addPreference(
            Preference(requireContext()).apply {
                isPersistent = false
                title = getString(R.string.plugin_config_open_custom)
                setOnPreferenceClickListener {
                    launchCustomSettings(component)
                    true
                }
            }
        )
    }

    private fun launchCustomSettings(component: ComponentName) {
        try {
            startActivity(
                Intent().apply {
                    this.component = component
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.plugin_config_custom_unavailable))
        } catch (_: SecurityException) {
            toast(getString(R.string.plugin_config_custom_unavailable))
        }
    }

    private fun buildPreference(item: PluginConfigItem, store: PluginConfigStore): Preference =
        when (item.type) {
            PluginConfigType.BOOLEAN -> SwitchPreferenceCompat(requireContext()).apply {
                key = item.key
                title = item.title
                summary = item.summary
                isPersistent = false
                isChecked = store.getBoolean(item.key, item.defaultValue.toBooleanOrDefault())
                setOnPreferenceChangeListener { _, newValue ->
                    store.putBoolean(item.key, newValue as Boolean)
                    true
                }
            }

            PluginConfigType.TEXT -> PluginConfigInputPreference(
                requireContext(),
                item,
                store.getString(item.key, item.defaultValue).orEmpty()
            ).apply {
                renderTextSummary(this, item, text)
                setOnPreferenceChangeListener { preference, newValue ->
                    val value = newValue as String
                    if (item.required && value.isBlank()) {
                        toast(getString(R.string.plugin_config_required))
                        return@setOnPreferenceChangeListener false
                    }
                    store.putString(item.key, value)
                    renderTextSummary(preference as EditTextPreference, item, value)
                    true
                }
            }

            PluginConfigType.SECRET -> PluginConfigInputPreference(
                requireContext(),
                item,
                store.getString(item.key, item.defaultValue).orEmpty()
            ).apply {
                renderTextSummary(this, item, text)
                setOnPreferenceChangeListener { preference, newValue ->
                    val value = (newValue as String).trim()
                    if (item.required && value.isBlank()) {
                        toast(getString(R.string.plugin_config_required))
                        return@setOnPreferenceChangeListener false
                    }
                    store.putString(item.key, value)
                    renderTextSummary(preference as EditTextPreference, item, value)
                    true
                }
            }

            PluginConfigType.NUMBER -> PluginConfigInputPreference(
                requireContext(),
                item,
                store.getInt(
                    item.key,
                    item.defaultValue?.toIntOrNull() ?: item.min ?: 0
                ).toString()
            ).apply {
                renderTextSummary(this, item, text)
                setOnPreferenceChangeListener { preference, newValue ->
                    val raw = (newValue as String).trim()
                    val parsed = raw.toIntOrNull()
                    if (parsed == null || !withinRange(parsed, item)) {
                        toast(rangeMessage(item))
                        return@setOnPreferenceChangeListener false
                    }
                    store.putInt(item.key, parsed)
                    renderTextSummary(preference as EditTextPreference, item, parsed.toString())
                    true
                }
            }

            PluginConfigType.CHOICE -> ListPreference(requireContext()).apply {
                key = item.key
                title = item.title
                isPersistent = false
                entries = item.choices.map { it.label }.toTypedArray()
                entryValues = item.choices.map { it.value }.toTypedArray()
                val current = store.getString(item.key, item.defaultValue)
                    ?: item.choices.firstOrNull()?.value
                value = current
                summary = item.choices.firstOrNull { it.value == current }?.label
                    ?: item.summary
                setOnPreferenceChangeListener { preference, newValue ->
                    val value = newValue as String
                    store.putString(item.key, value)
                    (preference as ListPreference).summary =
                        item.choices.firstOrNull { it.value == value }?.label ?: value
                    true
                }
            }
        }

    private fun renderTextSummary(
        preference: EditTextPreference,
        item: PluginConfigItem,
        value: String?
    ) {
        preference.summary = when {
            value.isNullOrBlank() -> item.summary.orEmpty()
            item.type == PluginConfigType.SECRET -> getString(R.string.plugin_config_secret_set)
            else -> value
        }
    }

    private fun withinRange(value: Int, item: PluginConfigItem): Boolean {
        val min = item.min
        val max = item.max
        if (min != null && value < min) return false
        if (max != null && value > max) return false
        return true
    }

    private fun rangeMessage(item: PluginConfigItem): String {
        val min = item.min
        val max = item.max
        return when {
            min != null && max != null -> getString(R.string.plugin_config_number_range, min, max)
            min != null -> getString(R.string.plugin_config_number_min, min)
            max != null -> getString(R.string.plugin_config_number_max, max)
            else -> getString(R.string.plugin_config_number_invalid)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ARG_PLUGIN_PACKAGE = "org.autojs.autojs.plugin.arg.PACKAGE"

        fun newInstance(packageName: String): PluginConfigFragment =
            PluginConfigFragment().apply {
                arguments = Bundle().apply { putString(ARG_PLUGIN_PACKAGE, packageName) }
            }
    }
}

private fun String?.toBooleanOrDefault(): Boolean = when (this?.trim()?.lowercase()) {
    "true", "1", "yes" -> true
    else -> false
}

/**
 * 配置项输入框：把 Preference 默认的系统对话框输入框换成 Material outlined 输入框。
 *
 * 默认实现只是 AlertDialog 里一个裸 EditText —— 没有浮动标签、没有错误提示、
 * 键盘类型也不区分。这里把示例值做成浮动标签、摘要做成 helper text，
 * 校验错误直接标在输入框上，而不是等点确定才弹 Toast。
 */
private class PluginConfigInputPreference(
    context: Context,
    private val item: PluginConfigItem,
    initial: String
) : EditTextPreference(context) {

    init {
        key = item.key
        title = item.title
        // DialogPreference 只在 mDialogTitle 非空时才设置对话框标题，默认没有标题，
        // 光看输入框和 helper text 不容易确认自己在改哪一项，这里显式补上。
        setDialogTitle(item.title)
        isPersistent = false
        text = initial
        setDialogLayoutResource(R.layout.dialog_plugin_config_input)
        setOnBindEditTextListener { edit ->
            val layout = edit.rootView.findViewById<TextInputLayout>(R.id.input_layout)
            if (layout != null) {
                layout.hint = item.placeholder ?: item.title
                layout.helperText = item.summary
                if (item.type == PluginConfigType.SECRET) {
                    layout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                }
                layout.error = validate(edit.text?.toString().orEmpty())
            }
            edit.imeOptions = EditorInfo.IME_ACTION_DONE
            when (item.type) {
                PluginConfigType.NUMBER -> {
                    edit.inputType = InputType.TYPE_CLASS_NUMBER
                    // 端口最多 5 位，按配置的上下界推导长度上限，避免输入超长数字。
                    val maxDigits = (item.max ?: item.min ?: 999_999).toString().length
                    edit.filters = arrayOf(InputFilter.LengthFilter(maxDigits))
                }
                PluginConfigType.TEXT -> {
                    edit.inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_URI or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                else -> Unit
            }
            edit.doAfterTextChanged { editable ->
                layout?.error = validate(editable?.toString().orEmpty())
            }
        }
    }

    private fun validate(value: String): String? {
        val raw = value.trim()
        if (raw.isEmpty()) {
            return if (item.required) context.getString(R.string.plugin_config_required) else null
        }
        if (item.type != PluginConfigType.NUMBER) {
            return null
        }
        val parsed = raw.toIntOrNull()
            ?: return context.getString(R.string.plugin_config_number_invalid)
        val min = item.min
        val max = item.max
        return when {
            min != null && max != null && (parsed < min || parsed > max) ->
                context.getString(R.string.plugin_config_number_range, min, max)
            min != null && parsed < min ->
                context.getString(R.string.plugin_config_number_min, min)
            max != null && parsed > max ->
                context.getString(R.string.plugin_config_number_max, max)
            else -> null
        }
    }
}

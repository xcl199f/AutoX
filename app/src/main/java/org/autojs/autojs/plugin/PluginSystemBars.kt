package org.autojs.autojs.plugin

import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 按当前主题明暗显式指定状态栏 / 导航栏图标颜色。
 *
 * targetSdk=35 强制 edge-to-edge 之后，系统不再根据窗口背景自动推断图标明暗：
 * 浅色背景配上默认的白色图标，状态栏上的时间、电量就完全看不见了。
 * 这里跟随 DayNight 配置：亮色主题用深色图标，暗色主题用浅色图标。
 */
internal fun AppCompatActivity.applySystemBarAppearance() {
    val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = !isNight
        isAppearanceLightNavigationBars = !isNight
    }
}

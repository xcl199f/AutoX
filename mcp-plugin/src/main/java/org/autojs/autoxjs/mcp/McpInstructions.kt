package org.autojs.autoxjs.mcp

/**
 * 通过 MCP `initialize` 返回的 `instructions` 字段下发给 AI 客户端的使用须知。
 *
 * 客户端（如 Claude Desktop）会把这段文本注入模型上下文，用来告诉 AI 使用本服务时
 * 的前置条件、工具能力与已知坑。注意：是否透传取决于客户端实现；工具级 description
 * 仍是最稳的兜底，两者应保持一致。
 */
object McpInstructions {

    val TEXT: String = """
        你在通过 AutoX MCP 服务驱动安卓手机上的 AutoX.js 运行时：远程把 JS 脚本推到手机执行，并取回日志/截图。

        【前置条件 —— 不满足就会报错，先排查这些】
        - 手机上的 AutoX 脚本引擎必须已初始化；否则 run_script / run_script_file 会报
          "lateinit property instance has not been initialized"。
        - 手机必须已开启 AutoX 的无障碍权限；MCP 与设备必须连通。

        【工具能力】
        - 执行与状态：run_script / run_script_file 执行脚本，job_status 轮询，logs 取任务状态。
        - 脚本输出日志：console_logs 取脚本 console 输出；首次用 recent，之后用 sinceId=nextCursor 只拉新增。
        - 脚本管理：save_script / list_scripts / read_script / delete_script / rename_script。
        - 界面与输入：screenshot / get_recent_screenshot 取截图，ocr 做文字识别，
          get_ui_tree / find_element / find_elements 取控件树与定位，
          tap / swipe / app_control 直接操控，get_foreground_app / get_current_activity 查当前界面。
        - device_info：查分辨率、型号等。
        - 维护：restart_mcp 重启插件运行时进程（重装插件 APK 后让新代码生效）。
          端口会中断数秒后自动恢复，正在运行的脚本会被中断；不需要手动重开宿主。

        【已知坑】
        - 偶发 429 限流 → 等几秒再重试，不要狂刷。
        - 脚本里用 captureScreen() 截的图【抓不到 floaty 浮窗层】；要核对浮窗 UI 只能真机肉眼看，
          不要用截图像素去量。
        - 脚本里 images.getBitmap() 的像素是 ARGB_8888，取色用
          r=(v>>16)&0xff、g=(v>>8)&0xff、b=v&0xff。
        - screenshot 返回的 path 是手机端私有缓存路径（/data/data/<包名>/cache/），PC 侧无法直接读取。
          取图三条路，按 token 成本从低到高：
          ① 优先用 get_ui_tree / find_elements / get_foreground_app 拿结构化信息，几百 token 就够，不要动辄截图；
          ② 需要看图时用返回的 url 下载（GET <url>；若配置了访问令牌，需带 X-Token 请求头），
             图不进上下文，成本最低；
          ③ 必须内联才用 inline=true，此时图像以标准 MCP image 内容块返回，长边默认 720（约 300 token）。
             需要看清细节时调大 maxDimension（如 1080，约 700 token），别一上来就传 0 取原图。
             注意：inline 需服务端开启「允许返回 Base64 图像」（默认关闭）。
          截图文件仅保留在缓存目录，可能被系统清理，需要时请及时下载。
        - 后台脚本里调用 requestScreenCapture() 会因 MediaProjection 需要前台 Activity 而永久阻塞；
          后台场景请改用 screenshot 工具。

        【工作习惯】
        - 跑脚本前先用 device_info 确认分辨率。
        - 改完先在本地用 node 校验语法/纯逻辑，再 run_script_file 上机；用 logs 看输出。
        - 一出错先看是不是上面「前置条件」没满足，再怀疑脚本逻辑。
    """.trimIndent()
}

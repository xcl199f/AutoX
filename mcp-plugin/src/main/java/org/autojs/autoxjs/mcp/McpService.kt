package org.autojs.autoxjs.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.autojs.autoxjs.mcp.tool.ToolDefinition
import org.autojs.autoxjs.mcp.tool.ToolRegistry
import org.autojs.autoxjs.mcp.tool.ToolSchemas
import org.autojs.autoxjs.mcp.tools.AppControlTool
import org.autojs.autoxjs.mcp.tools.CancelJobTool
import org.autojs.autoxjs.mcp.tools.ConsoleLogsTool
import org.autojs.autoxjs.mcp.tools.DeleteScriptTool
import org.autojs.autoxjs.mcp.tools.DeviceInfoTool
import org.autojs.autoxjs.mcp.tools.FindElementTool
import org.autojs.autoxjs.mcp.tools.FindElementsTool
import org.autojs.autoxjs.mcp.tools.GetCurrentActivityTool
import org.autojs.autoxjs.mcp.tools.GetForegroundAppTool
import org.autojs.autoxjs.mcp.tools.GetRecentScreenshotTool
import org.autojs.autoxjs.mcp.tools.GetUiTreeTool
import org.autojs.autoxjs.mcp.tools.JobStatusTool
import org.autojs.autoxjs.mcp.tools.JobTracker
import org.autojs.autoxjs.mcp.tools.ListAppsTool
import org.autojs.autoxjs.mcp.tools.ListSamplesTool
import org.autojs.autoxjs.mcp.tools.ListScriptDirsTool
import org.autojs.autoxjs.mcp.tools.ListScriptsTool
import org.autojs.autoxjs.mcp.tools.LogsTool
import org.autojs.autoxjs.mcp.tools.McpToolContext
import org.autojs.autoxjs.mcp.tools.OcrTool
import org.autojs.autoxjs.mcp.tools.ReadSampleTool
import org.autojs.autoxjs.mcp.tools.ReadScriptTool
import org.autojs.autoxjs.mcp.tools.RenameScriptTool
import org.autojs.autoxjs.mcp.tools.RestartMcpTool
import org.autojs.autoxjs.mcp.tools.RunScriptTool
import org.autojs.autoxjs.mcp.tools.RunScriptFileTool
import org.autojs.autoxjs.mcp.tools.SaveScriptTool
import org.autojs.autoxjs.mcp.tools.ScreenshotTool
import org.autojs.autoxjs.mcp.tools.SwipeTool
import org.autojs.autoxjs.mcp.tools.TapTool
import org.autojs.autoxjs.mcp.tools.UpdateScriptTool

/**
 * Facade to start/stop MCP server with default tools registered.
 */
class McpService internal constructor(
    private val context: Context,
    private val restart: () -> Unit,
    private val healthSupervisor: McpSelfHealthSupervisor,
    private val healthProbe: McpHealthProbe,
    private val probeIntervalMillis: Long
) {
    constructor(context: Context) : this(
        context = context,
        restart = {},
        healthSupervisor = McpHealthStatus.runtime(),
        healthProbe = McpLocalHealthProbe(),
        probeIntervalMillis = SELF_PROBE_INTERVAL_MILLIS
    )

    constructor(context: Context, restart: () -> Unit) : this(
        context = context,
        restart = restart,
        healthSupervisor = McpHealthStatus.runtime(),
        healthProbe = McpLocalHealthProbe(),
        probeIntervalMillis = SELF_PROBE_INTERVAL_MILLIS
    )

    private val tracker = JobTracker()
    private val registry = ToolRegistry()
    private val runtimeProvider = McpRuntimeProvider()
    private val screenshotStore = ScreenshotStore()
    @Volatile
    private var currentConfig: McpConfig = McpConfig()
    private val toolContext = McpToolContext(
        appContext = context.applicationContext,
        runtimeProvider = runtimeProvider,
        screenshotStore = screenshotStore,
        baseUrlProvider = { McpUrls.baseUrl(currentConfig) },
        restartProvider = { restart() }
    ) { currentConfig }
    private var server: McpServer? = null
    private var activeConfig: McpConfig? = null
    // 可复用的作用域，stop() 里不要 cancel：SupervisorJob 一旦被取消，
    // 之后再 start() 时 launch 出来的健康探测/空闲监控会立即结束，
    // 服务虽重启但这些后台任务永久失效。stop() 已逐个 cancel 两个 Job。
    private val healthScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var healthProbeJob: Job? = null
    private var healthSessionStarted = false
    private var idleWatchJob: Job? = null

    /**
     * 因空闲而停掉监听时保存的配置，用于 [resumeFromIdle] 恢复。
     *
     * 为 null 表示当前不是「空闲休眠」状态。
     */
    @Volatile
    private var idleSleepConfig: McpConfig? = null

    init {
        registerDefaultTools()
    }

    @Synchronized
    fun start(config: McpConfig): Boolean {
        currentConfig = config
        if (!config.enabled) {
            stop()
            return false
        }
        idleSleepConfig = null
        if (!healthSessionStarted) {
            healthSupervisor.startSession()
            healthSessionStarted = true
        }
        if (server == null) {
            server = McpServer(context.applicationContext, registry, healthSupervisor)
        }
        if (activeConfig == config && server?.isRunning == true) {
            ensureHealthProbeLoop()
            ensureIdleWatchLoop()
            return true
        }
        // Keep the desired config after a bind failure so the probe loop can retry only Ktor.
        activeConfig = config
        val started = server?.start(config) == true
        if (started) {
            Log.i(TAG, "MCP service started")
        } else {
            Log.e(TAG, "MCP service failed to start")
        }
        ensureHealthProbeLoop()
        ensureIdleWatchLoop()
        return started
    }

    @Synchronized
    fun stop() {
        healthProbeJob?.cancel()
        healthProbeJob = null
        idleWatchJob?.cancel()
        idleWatchJob = null
        idleSleepConfig = null
        server?.stop()
        server = null
        activeConfig = null
        healthSupervisor.stopSession()
        healthSessionStarted = false
        runtimeProvider.close()
        Log.i(TAG, "MCP service stopped")
    }

    /**
     * 从空闲休眠中恢复监听。
     *
     * 返回 true 表示确实有一个休眠中的服务被重新拉起；没有休眠时返回 false，
     * 调用方无需处理。宿主在界面回到前台时调用。
     */
    @Synchronized
    fun resumeFromIdle(): Boolean {
        val config = idleSleepConfig ?: return false
        Log.i(TAG, "resuming MCP server from idle sleep")
        idleSleepConfig = null
        return start(config)
    }

    /** 当前是否处于空闲休眠（监听已停止，等待唤醒）。 */
    val isIdleSleeping: Boolean
        get() = idleSleepConfig != null

    private fun ensureIdleWatchLoop() {
        if (idleWatchJob?.isActive == true) {
            return
        }
        idleWatchJob = healthScope.launch {
            while (isActive) {
                delay(IDLE_CHECK_INTERVAL_MILLIS)
                runCatching { enterIdleSleepIfNeeded() }
                    .onFailure { Log.w(TAG, "idle check failed", it) }
            }
        }
    }

    /**
     * 检查是否该进入空闲休眠。
     *
     * 只停 Ktor 引擎，保留 [runtimeProvider] 等对象：恢复时不必重新初始化运行时，
     * 唤醒耗时更短。
     */
    private fun enterIdleSleepIfNeeded() {
        synchronized(this) {
            val config = activeConfig ?: return
            if (!config.idleShutdown) {
                return
            }
            val running = server ?: return
            val idleMillis = System.currentTimeMillis() - running.lastActivityAt
            val limitMillis = config.idleMinutes * 60_000L
            if (idleMillis < limitMillis) {
                return
            }
            Log.i(TAG, "idle for ${idleMillis / 1000}s (limit ${config.idleMinutes}min), stopping listener")
            healthProbeJob?.cancel()
            healthProbeJob = null
            running.stop()
            server = null
            activeConfig = null
            healthSupervisor.stopSession()
            healthSessionStarted = false
            idleSleepConfig = config
        }
    }

    private fun ensureHealthProbeLoop() {
        if (healthProbeJob?.isActive == true) {
            return
        }
        healthProbeJob = healthScope.launch {
            while (isActive) {
                delay(probeIntervalMillis)
                probeAndRecoverIfNeeded()
            }
        }
    }

    private suspend fun probeAndRecoverIfNeeded() {
        val probeTarget = synchronized(this) {
            val config = activeConfig ?: return
            val identity = healthSupervisor.expectedIdentity() ?: return
            McpSelfProbeTarget(config, identity)
        }
        val result = healthProbe.probe(probeTarget.config, probeTarget.identity)
        // start(), stop(), and engine restart use this same monitor.
        synchronized(this) {
            when (
                recordMcpProbeResultIfCurrent(
                    activeConfig = activeConfig,
                    currentIdentity = healthSupervisor.expectedIdentity(),
                    probeTarget = probeTarget,
                    result = result,
                    recordSuccess = healthSupervisor::recordProbeSuccess,
                    recordFailure = healthSupervisor::recordProbeFailure
                )
            ) {
                McpRecoveryAction.RESTART_ENGINE -> restartEngineOnly(
                    expectedIdentity = probeTarget.identity,
                    expectedConfig = probeTarget.config
                )
                McpRecoveryAction.NONE,
                McpRecoveryAction.RATE_LIMITED,
                null -> Unit
            }
        }
    }

    @Synchronized
    private fun restartEngineOnly(
        expectedIdentity: McpEngineIdentity,
        expectedConfig: McpConfig
    ) {
        val currentIdentity = healthSupervisor.expectedIdentity()
        if (!runMcpEngineRestartIfCurrent(
                activeConfig = activeConfig,
                currentIdentity = currentIdentity,
                expectedConfig = expectedConfig,
                expectedIdentity = expectedIdentity
            ) { config ->
                Log.w(TAG, "Restarting embedded MCP HTTP engine after failed self-probes")
                server?.start(config)
            }
        ) {
            return
        }
    }

    private fun registerDefaultTools() {
        registry.apply {
            register(
                ToolDefinition(
                    name = "device_info",
                    title = "Device Info",
                    description = "Return device information and locale.",
                    inputSchema = ToolSchemas.emptyObject()
                ),
                DeviceInfoTool()
            )
            register(
                ToolDefinition(
                    name = "run_script",
                    title = "Run Script",
                    description = "Run an AutoJs script and return a job id.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "script" to ToolSchemas.stringSchema("JavaScript source to execute."),
                            "name" to ToolSchemas.stringSchema("Optional job name."),
                            "mode" to ToolSchemas.stringSchema("Optional runtime mode."),
                            "timeoutMillis" to ToolSchemas.intSchema("Optional timeout in milliseconds.")
                        ),
                        required = listOf("script")
                    )
                ),
                RunScriptTool(context, tracker)
            )
            register(
                ToolDefinition(
                    name = "job_status",
                    title = "Job Status",
                    description = "Get status for a script job.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf("jobId" to ToolSchemas.intSchema("Job id.")),
                        required = listOf("jobId")
                    )
                ),
                JobStatusTool(tracker)
            )
            register(
                ToolDefinition(
                    name = "cancel_job",
                    title = "Cancel Job",
                    description = "Cancel one running MCP job and wait for its script engine to stop.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf("jobId" to ToolSchemas.intSchema("Job id.")),
                        required = listOf("jobId")
                    )
                ),
                CancelJobTool(tracker)
            )
            register(
                ToolDefinition(
                    name = "logs",
                    title = "Logs",
                    description = "Get recent job logs.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf("recent" to ToolSchemas.intSchema("Max number of log entries.")),
                        required = emptyList()
                    )
                ),
                LogsTool(tracker)
            )
            register(
                ToolDefinition(
                    name = "console_logs",
                    title = "Console Logs",
                    description = "Read script console output (console.log/print) from the AutoX global console. " +
                        "First call with {recent:N} for context and note nextCursor; then call with {sinceId:nextCursor} " +
                        "after each run to fetch only new lines. Response includes nextCursor, firstId, lastId, " +
                        "dropped (lines evicted before cursor), restarted (process restarted, cursor reset).",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "recent" to ToolSchemas.intSchema("Return the last N lines (default 100). Use on first call."),
                            "sinceId" to ToolSchemas.intSchema("Only return lines with id > sinceId. Pass the previous nextCursor.")
                        ),
                        required = emptyList()
                    )
                ),
                ConsoleLogsTool()
            )
            register(
                ToolDefinition(
                    name = "restart_mcp",
                    title = "Restart MCP Runtime",
                    description = "Restart the plugin runtime process (:script) so the latest installed plugin APK " +
                        "takes effect. The host rebuilds the service automatically (START_STICKY); " +
                        "the MCP port is down for a few seconds. Running scripts are interrupted. " +
                        "Use this after reinstalling the plugin APK.",
                    inputSchema = ToolSchemas.emptyObject()
                ),
                RestartMcpTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "list_apps",
                    title = "List Apps",
                    description = "List installed apps with optional filtering.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "query" to ToolSchemas.stringSchema("Filter by package name or label."),
                            "includeSystem" to ToolSchemas.booleanSchema("Include system apps."),
                            "limit" to ToolSchemas.intSchema("Max number of results.")
                        )
                    )
                ),
                ListAppsTool(context)
            )
            register(
                ToolDefinition(
                    name = "save_script",
                    title = "Save Script",
                    description = "Save a script file into AutoJs default scripts directory.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "script" to ToolSchemas.stringSchema("JavaScript source to save."),
                            "name" to ToolSchemas.stringSchema("Optional script file name."),
                            "overwrite" to ToolSchemas.booleanSchema("Overwrite if file exists.")
                        ),
                        required = listOf("script")
                    )
                ),
                SaveScriptTool(context)
            )
            register(
                ToolDefinition(
                    name = "list_samples",
                    title = "List Samples",
                    description = "List built-in sample scripts shipped with AutoJs.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "query" to ToolSchemas.stringSchema("Filter by sample path or name."),
                            "limit" to ToolSchemas.intSchema("Max number of results.")
                        )
                    )
                ),
                ListSamplesTool(context)
            )
            register(
                ToolDefinition(
                    name = "read_sample",
                    title = "Read Sample",
                    description = "Read a built-in sample script by path.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "path" to ToolSchemas.stringSchema("Sample path under assets/sample.")
                        ),
                        required = listOf("path")
                    )
                ),
                ReadSampleTool(context)
            )
            register(
                ToolDefinition(
                    name = "read_script",
                    title = "Read Script",
                    description = "Read a script file from AutoJs scripts directory.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "path" to ToolSchemas.stringSchema("Script file path relative to scripts dir.")
                        ),
                        required = listOf("path")
                    )
                ),
                ReadScriptTool(context)
            )
            register(
                ToolDefinition(
                    name = "update_script",
                    title = "Update Script",
                    description = "Overwrite an existing script file in AutoJs scripts directory.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "path" to ToolSchemas.stringSchema("Script file path relative to scripts dir."),
                            "script" to ToolSchemas.stringSchema("JavaScript source to save.")
                        ),
                        required = listOf("path", "script")
                    )
                ),
                UpdateScriptTool(context)
            )
            register(
                ToolDefinition(
                    name = "delete_script",
                    title = "Delete Script",
                    description = "Delete a script file from AutoJs scripts directory.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "path" to ToolSchemas.stringSchema("Script file path relative to scripts dir.")
                        ),
                        required = listOf("path")
                    )
                ),
                DeleteScriptTool(context)
            )
            register(
                ToolDefinition(
                    name = "rename_script",
                    title = "Rename Script",
                    description = "Rename a script file in AutoJs scripts directory.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "path" to ToolSchemas.stringSchema("Script file path relative to scripts dir."),
                            "newName" to ToolSchemas.stringSchema("New script file name.")
                        ),
                        required = listOf("path", "newName")
                    )
                ),
                RenameScriptTool(context)
            )
            register(
                ToolDefinition(
                    name = "run_script_file",
                    title = "Run Script File",
                    description = "Run a script file from AutoJs scripts directory or absolute path.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "path" to ToolSchemas.stringSchema("Script file path or name under scripts dir."),
                            "name" to ToolSchemas.stringSchema("Optional job name.")
                        ),
                        required = listOf("path")
                    )
                ),
                RunScriptFileTool(context, tracker)
            )
            register(
                ToolDefinition(
                    name = "list_scripts",
                    title = "List Scripts",
                    description = "List scripts from AutoJs scripts directory.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "query" to ToolSchemas.stringSchema("Filter by file name or path."),
                            "limit" to ToolSchemas.intSchema("Max number of results."),
                            "recursive" to ToolSchemas.booleanSchema("List subdirectories.")
                        )
                    )
                ),
                ListScriptsTool(context)
            )
            register(
                ToolDefinition(
                    name = "list_script_dirs",
                    title = "List Script Dirs",
                    description = "List directories under AutoJs scripts directory.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "query" to ToolSchemas.stringSchema("Filter by directory name or path."),
                            "limit" to ToolSchemas.intSchema("Max number of results."),
                            "recursive" to ToolSchemas.booleanSchema("List subdirectories.")
                        )
                    )
                ),
                ListScriptDirsTool(context)
            )
            register(
                ToolDefinition(
                    name = "tap",
                    title = "Tap",
                    description = "Tap on screen at the given coordinates.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "x" to ToolSchemas.intSchema("X coordinate."),
                            "y" to ToolSchemas.intSchema("Y coordinate.")
                        ),
                        required = listOf("x", "y")
                    )
                ),
                TapTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "swipe",
                    title = "Swipe",
                    description = "Swipe from one coordinate to another.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "x1" to ToolSchemas.intSchema("Start X coordinate."),
                            "y1" to ToolSchemas.intSchema("Start Y coordinate."),
                            "x2" to ToolSchemas.intSchema("End X coordinate."),
                            "y2" to ToolSchemas.intSchema("End Y coordinate."),
                            "duration" to ToolSchemas.intSchema("Duration in milliseconds.")
                        ),
                        required = listOf("x1", "y1", "x2", "y2")
                    )
                ),
                SwipeTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "find_element",
                    title = "Find Element",
                    description = "Find a UI element by text, id, desc, or className.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "text" to ToolSchemas.stringSchema("Match element text."),
                            "id" to ToolSchemas.stringSchema("Match resource id."),
                            "desc" to ToolSchemas.stringSchema("Match content description."),
                            "className" to ToolSchemas.stringSchema("Match class name."),
                            "timeoutMillis" to ToolSchemas.intSchema("Search timeout in milliseconds.")
                        )
                    )
                ),
                FindElementTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "find_elements",
                    title = "Find Elements",
                    description = "Find multiple UI elements by text, id, desc, or className.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "text" to ToolSchemas.stringSchema("Match element text."),
                            "id" to ToolSchemas.stringSchema("Match resource id."),
                            "desc" to ToolSchemas.stringSchema("Match content description."),
                            "className" to ToolSchemas.stringSchema("Match class name."),
                            "timeoutMillis" to ToolSchemas.intSchema("Search timeout in milliseconds."),
                            "limit" to ToolSchemas.intSchema("Max number of results.")
                        )
                    )
                ),
                FindElementsTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "screenshot",
                    title = "Screenshot",
                    description = "Capture a screenshot. By default the response only contains metadata: device-local " +
                        "'path' and a downloadable 'url' (GET <url>, pass X-Token header when a token is configured) " +
                        "that a PC client can fetch directly — this is the cheapest option in tokens. " +
                        "Set inline=true to return the image itself as a standard MCP image content block " +
                        "(requires 'allow base64' enabled); use maxDimension to trade detail for tokens.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "inline" to ToolSchemas.booleanSchema(
                                "Return the image as an MCP image content block. Alias: asBase64."
                            ),
                            "asBase64" to ToolSchemas.booleanSchema(
                                "Deprecated alias of inline."
                            ),
                            "maxDimension" to ToolSchemas.intSchema(
                                "Long-edge pixel size of the inline image, default 720 (~311 tokens for a " +
                                    "1080x2400 screen). Range 200..4096; pass 0 for full resolution."
                            )
                        )
                    )
                ),
                ScreenshotTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "ocr",
                    title = "OCR",
                    description = "Run OCR on a screenshot or image file.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "source" to ToolSchemas.stringSchema("Use 'screenshot' for last screenshot."),
                            "path" to ToolSchemas.stringSchema("Absolute file path to image."),
                            "language" to ToolSchemas.stringSchema("OCR language code.")
                        )
                    )
                ),
                OcrTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "app_control",
                    title = "App Control",
                    description = "Launch or force-stop an app.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "action" to ToolSchemas.stringSchema("launch | bring_to_front | force_stop"),
                            "packageName" to ToolSchemas.stringSchema("Target package name.")
                        ),
                        required = listOf("action", "packageName")
                    )
                ),
                AppControlTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "get_foreground_app",
                    title = "Get Foreground App",
                    description = "Get the foreground package name.",
                    inputSchema = ToolSchemas.emptyObject()
                ),
                GetForegroundAppTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "get_current_activity",
                    title = "Get Current Activity",
                    description = "Get the current activity class name.",
                    inputSchema = ToolSchemas.emptyObject()
                ),
                GetCurrentActivityTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "get_ui_tree",
                    title = "Get UI Tree",
                    description = "Capture the current UI tree.",
                    inputSchema = ToolSchemas.emptyObject()
                ),
                GetUiTreeTool(toolContext)
            )
            register(
                ToolDefinition(
                    name = "get_recent_screenshot",
                    title = "Get Recent Screenshot",
                    description = "Return metadata (path, width, height, timestamp) of the most recent screenshot, plus a " +
                        "downloadable 'url' a PC client can fetch directly. Set inline=true to return the image itself " +
                        "as a standard MCP image content block (requires 'allow base64' enabled); use maxDimension " +
                        "to trade detail for tokens.",
                    inputSchema = ToolSchemas.objectSchema(
                        mapOf(
                            "inline" to ToolSchemas.booleanSchema(
                                "Return the image as an MCP image content block. Alias: asBase64."
                            ),
                            "asBase64" to ToolSchemas.booleanSchema(
                                "Deprecated alias of inline."
                            ),
                            "maxDimension" to ToolSchemas.intSchema(
                                "Long-edge pixel size of the inline image, default 720. Range 200..4096; " +
                                    "pass 0 for full resolution."
                            )
                        )
                    )
                ),
                GetRecentScreenshotTool(toolContext)
            )
        }
    }

    companion object {
        private const val TAG = "McpService"
        internal const val SELF_PROBE_INTERVAL_MILLIS = 20_000L

        /** 空闲检查间隔。实际休眠延迟 = 配置的分钟数 + 最多这么多秒。 */
        internal const val IDLE_CHECK_INTERVAL_MILLIS = 60_000L
    }
}

internal data class McpSelfProbeTarget(
    val config: McpConfig,
    val identity: McpEngineIdentity
)

internal fun recordMcpProbeResultIfCurrent(
    activeConfig: McpConfig?,
    currentIdentity: McpEngineIdentity?,
    probeTarget: McpSelfProbeTarget,
    result: McpProbeResult,
    recordSuccess: (McpEngineIdentity) -> Unit,
    recordFailure: (McpEngineIdentity, String) -> McpRecoveryAction
): McpRecoveryAction? {
    if (activeConfig != probeTarget.config || currentIdentity != probeTarget.identity) {
        return null
    }
    return when (result) {
        McpProbeResult.Success -> {
            recordSuccess(probeTarget.identity)
            McpRecoveryAction.NONE
        }

        is McpProbeResult.Failure ->
            recordFailure(probeTarget.identity, result.errorCode)
    }
}

internal fun runMcpEngineRestartIfCurrent(
    activeConfig: McpConfig?,
    currentIdentity: McpEngineIdentity?,
    expectedConfig: McpConfig,
    expectedIdentity: McpEngineIdentity,
    restart: (McpConfig) -> Unit
): Boolean {
    if (activeConfig != expectedConfig || currentIdentity != expectedIdentity) {
        return false
    }
    restart(expectedConfig)
    return true
}

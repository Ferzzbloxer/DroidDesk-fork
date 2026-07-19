package com.orailnoor.droiddesk

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.content.Context
import android.net.Uri
import android.provider.Settings
import com.orailnoor.droiddesk.service.DroidDeskService
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.RootShell
import com.orailnoor.droiddesk.view.AndroidSurfaceViewFactory
import com.orailnoor.droiddesk.x11.X11ServerService
import kotlin.concurrent.thread
import android.util.Log
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.droiddesk/core"
        private const val TAG = "MainActivity"
    }

    private lateinit var linuxRuntime: LinuxRuntime
    private lateinit var chrootRuntime: ChrootRuntime
    private lateinit var globalLogFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        linuxRuntime = LinuxRuntime(this)
        chrootRuntime = ChrootRuntime(this)

        // 1. INITIALIZE GLOBAL LOGGER
        // This places the log file in the app's accessible external storage
        globalLogFile = File(filesDir, "droiddesk_diagnostic_log.txt")
        
        try {
            globalLogFile.writeText("=== DROIDDESK DIAGNOSTIC LOG ===\n")
            globalLogFile.appendText("Session Started: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
            globalLogFile.appendText("Device: ${Build.BRAND} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})\n\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize log file", e)
        }

        if (intent.getBooleanExtra("autoSetup", false)) {
            runAutoChrootSetup()
        }
    }

    // 2. THE LOGGER FUNCTION
    private fun appendLog(tag: String, message: String) {
        try {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val cleanMsg = message.replace("\r", "")
            globalLogFile.appendText("[$time] [$tag] $cleanMsg")
            if (!cleanMsg.endsWith("\n")) globalLogFile.appendText("\n")
        } catch (e: Exception) {
            Log.e(TAG, "Logger failed to write", e)
        }
    }

    private fun runAutoChrootSetup() {
        // Omitting auto-setup implementation for brevity, logs are placed in the main MethodChannel
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        flutterEngine
            .platformViewsController
            .registry
            .registerViewFactory("droiddesk-surface", AndroidSurfaceViewFactory())

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {

                // ── Runtime Status ──
                "getRuntimeStatus" -> {
                    val rooted = chrootRuntime.hasRoot()
                    result.success(mapOf(
                        "isBootstrapped" to if (rooted) chrootRuntime.isRootfsReady() else linuxRuntime.isBootstrapped(),
                        "isRunning" to if (rooted) chrootRuntime.isRunning() else linuxRuntime.isRunning(),
                        "hasRoot" to rooted,
                        "distro" to if (rooted) "ubuntu-chroot" else "termux-native",
                        "installedDE" to if (rooted) {
                            if (chrootRuntime.isDesktopInstalled()) "xfce4" else ""
                        } else {
                            linuxRuntime.getInstalledDE()
                        },
                        "rootfsPath" to if (rooted) chrootRuntime.getRootfsPath() else "",
                        "rootfsSizeMB" to if (rooted) chrootRuntime.getRootfsSizeMB() else 0L
                    ))
                }

                // ── Device Info ──
                "getDeviceInfo" -> {
                    result.success(mapOf(
                        "model" to Build.MODEL,
                        "brand" to Build.BRAND,
                        "androidVersion" to Build.VERSION.RELEASE,
                        "sdkVersion" to Build.VERSION.SDK_INT,
                        "cpuAbi" to Build.SUPPORTED_ABIS.firstOrNull(),
                        "gpuVendor" to getGpuVendor(),
                        "graphicsMode" to if (chrootRuntime.hasRoot()) "Software (llvmpipe)" else linuxRuntime.getGraphicsMode(),
                        "totalRamMB" to getTotalRam(),
                        "availableStorageMB" to getAvailableStorage()
                    ))
                }

                "checkRoot" -> {
                    thread {
                        val ok = chrootRuntime.hasRoot()
                        appendLog("SYS", "Root check performed. Result: $ok")
                        runOnUiThread { result.success(ok) }
                    }
                }

                "resetRootCache" -> {
                    RootShell(this).resetCache()
                    result.success(true)
                }

                // ── Chroot rootfs management (rooted) ──
                "downloadRootfs" -> {
                    thread {
                        try {
                            appendLog("SYS", "Starting Rootfs Download")
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var success = false
                            chrootRuntime.downloadRootfs { progress, status ->
                                appendLog("DL_PROG", "[$progress] $status")
                                runOnUiThread {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod("onDownloadProgress", mapOf("progress" to progress, "status" to status))
                                    }
                                }
                                if (progress >= 1.0 || progress < 0) {
                                    success = progress >= 1.0
                                    latch.countDown()
                                }
                            }
                            latch.await()
                            appendLog("SYS", "Rootfs Download Finished. Success: $success")
                            runOnUiThread { result.success(success) }
                        } catch (e: Exception) {
                            appendLog("SYS_ERR", Log.getStackTraceString(e))
                            runOnUiThread { result.success(false) }
                        }
                    }
                }

                "extractRootfs" -> {
                    thread {
                        try {
                            appendLog("SYS", "Starting Rootfs Extraction")
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var success = false
                            chrootRuntime.extractRootfs { progress, status ->
                                appendLog("EXT_PROG", "[$progress] $status")
                                runOnUiThread {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod("onExtractProgress", mapOf("progress" to progress, "status" to status))
                                    }
                                }
                                if (progress >= 1.0 || progress < 0) {
                                    success = progress >= 1.0
                                    latch.countDown()
                                }
                            }
                            latch.await()
                            appendLog("SYS", "Extraction Finished. Success: $success")
                            runOnUiThread { result.success(success) }
                        } catch (e: Exception) {
                            appendLog("SYS_ERR", Log.getStackTraceString(e))
                            runOnUiThread { result.success(false) }
                        }
                    }
                }

                "installDesktopEnvironment" -> {
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    appendLog("SYS", "Starting Chroot Desktop Install ($desktopEnv)")
                    
                    // Alert the user via the UI console where the log is saving
                    runOnUiThread {
                        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod("onTerminalOutput", mapOf("text" to "\n[System] Live diagnostic log saving to:\n${globalLogFile.absolutePath}\n\n"))
                    }

                    thread {
                        try {
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var success = false
                            chrootRuntime.installDesktopEnvironment(
                                desktopEnv,
                                { progress, status ->
                                    appendLog("INSTALL_PROG", "[$progress] $status")
                                    runOnUiThread {
                                        flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                            MethodChannel(messenger, CHANNEL).invokeMethod("onInstallProgress", mapOf("progress" to progress, "status" to status))
                                        }
                                    }
                                    if (progress >= 1.0 || progress < 0) {
                                        success = progress >= 1.0
                                        latch.countDown()
                                    }
                                },
                                { logChunk ->
                                    appendLog("APT_OUT", logChunk)
                                    runOnUiThread {
                                        flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                            MethodChannel(messenger, CHANNEL).invokeMethod("onTerminalOutput", mapOf("text" to logChunk))
                                        }
                                    }
                                }
                            )
                            latch.await()
                            appendLog("SYS", "Desktop Install Finished. Success: $success")
                            runOnUiThread { result.success(success) }
                        } catch (e: Exception) {
                            appendLog("SYS_ERR", Log.getStackTraceString(e))
                            runOnUiThread { result.success(false) }
                        }
                    }
                }

                // ── Native Termux desktop install (non-root fallback) ──
                "installDesktopNative" -> {
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    appendLog("SYS", "Starting Native (Non-Root) Desktop Install ($desktopEnv)")

                    runOnUiThread {
                        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod("onTerminalOutput", mapOf("text" to "\n[System] Live diagnostic log saving to:\n${globalLogFile.absolutePath}\n\n"))
                    }

                    thread {
                        linuxRuntime.setInstallLogSink { chunk ->
                            appendLog("TERMUX_OUT", chunk)
                            runOnUiThread {
                                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod("onTerminalOutput", mapOf("text" to chunk))
                            }
                        }
                        try {
                            val ok = linuxRuntime.installDesktopEnvironmentNative(desktopEnv) { progress, status ->
                                appendLog("TERMUX_PROG", "[$progress] $status")
                                runOnUiThread {
                                    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod("onInstallProgress", mapOf("progress" to progress, "status" to status))
                                }
                            }
                            appendLog("SYS", "Native Install Finished. Success: $ok")
                            runOnUiThread { result.success(ok) }
                        } catch (e: Exception) {
                            appendLog("SYS_ERR", Log.getStackTraceString(e))
                            runOnUiThread { result.success(false) }
                        } finally {
                            linuxRuntime.setInstallLogSink(null)
                        }
                    }
                }

                "getOptionalApps" -> {
                    val status = if (chrootRuntime.hasRoot()) chrootRuntime.getOptionalAppsStatus() else linuxRuntime.getOptionalAppsStatus()
                    result.success(status)
                }

                "installOptionalApp" -> {
                    val appId = call.argument<String>("appId") ?: ""
                    appendLog("SYS", "Installing Optional App: $appId")
                    
                    thread {
                        val logSink: (String) -> Unit = { chunk ->
                            appendLog("OPT_APP_OUT", chunk)
                            runOnUiThread {
                                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod("onTerminalOutput", mapOf("text" to chunk))
                            }
                        }
                        val progressSink: (Double, String) -> Unit = { progress, status ->
                            appendLog("OPT_APP_PROG", "[$progress] $status")
                            runOnUiThread {
                                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod("onOptionalInstallProgress", mapOf("progress" to progress, "status" to status))
                            }
                        }

                        val ok = if (chrootRuntime.hasRoot()) {
                            chrootRuntime.installOptionalApp(appId, progressSink, logSink)
                        } else {
                            linuxRuntime.setInstallLogSink(logSink)
                            try {
                                linuxRuntime.installOptionalApp(appId, progressSink)
                            } finally {
                                linuxRuntime.setInstallLogSink(null)
                            }
                        }
                        appendLog("SYS", "Optional App Install Finished. Success: $ok")
                        runOnUiThread { result.success(ok) }
                    }
                }

                // ── Start Linux session ──
                "startLinux" -> {
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    val mode = call.argument<String>("mode") ?: "x11"
                    var width = call.argument<Int>("width") ?: 1920
                    var height = call.argument<Int>("height") ?: 1080

                    appendLog("SYS", "Starting Linux Session: DE=$desktopEnv Mode=$mode")
                    if (height > 720) {
                        val scale = 720.0 / height
                        width = (width * scale).toInt()
                        height = 720
                    }

                    startForegroundService()

                    if (chrootRuntime.hasRoot()) {
                        thread {
                            if (!chrootRuntime.isRootfsReady()) {
                                appendLog("SYS_ERR", "Chroot rootfs not ready; cannot start session")
                                runOnUiThread { result.success(false) }
                                return@thread
                            }
                            runOnUiThread {
                                val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
                                    putExtra("startSession", true)
                                    putExtra("mode", "chroot")
                                    putExtra("de", desktopEnv)
                                }
                                startActivity(intent)
                                result.success(true)
                            }
                        }
                    } else {
                        thread {
                            linuxRuntime.extractBootstrapIfNeeded(applicationContext)
                            val installed = linuxRuntime.getInstalledDE()
                            val ready = installed == desktopEnv || linuxRuntime.installDesktopEnvironmentNative(desktopEnv)
                            if (!ready) {
                                appendLog("SYS_ERR", "Native Termux desktop setup failed; session was not launched")
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "Native Linux setup failed. Check the setup log.", Toast.LENGTH_LONG).show()
                                    result.success(false)
                                }
                                return@thread
                            }
                            runOnUiThread {
                                val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
                                    putExtra("startSession", true)
                                    putExtra("mode", "termux")
                                    putExtra("de", desktopEnv)
                                }
                                startActivity(intent)
                                result.success(true)
                            }
                        }
                    }
                }

                "launchDesktopActivity" -> {
                    val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java)
                    startActivity(intent)
                    result.success(true)
                }

                "stopLinux" -> {
                    appendLog("SYS", "Stopping Linux Session")
                    thread(name = "stop-linux-session") {
                        if (chrootRuntime.hasRoot() || chrootRuntime.isRunning()) {
                            chrootRuntime.stopSession()
                        }
                        linuxRuntime.stopSession()
                        stopService(Intent(this@MainActivity, X11ServerService::class.java))
                        stopForegroundService()
                        runOnUiThread { result.success(true) }
                    }
                }

                // ── Command execution ──
                "executeCommand" -> {
                    val command = call.argument<String>("command") ?: ""
                    appendLog("CMD_IN", command)
                    Thread {
                        val output = if (chrootRuntime.hasRoot()) {
                            chrootRuntime.executeCommand(command) { chunk ->
                                appendLog("CMD_OUT", chunk)
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod("onTerminalOutput", mapOf("text" to chunk))
                                    }
                                }
                            }
                        } else {
                            linuxRuntime.executeCommand(command) { chunk ->
                                appendLog("CMD_OUT", chunk)
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod("onTerminalOutput", mapOf("text" to chunk))
                                    }
                                }
                            }
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            result.success(output)
                        }
                    }.start()
                }

                "interruptCommand" -> {
                    appendLog("SYS", "Interrupting command")
                    linuxRuntime.interruptCommand()
                    result.success(true)
                }

                // ── System ──
                "requestBatteryOptimization" -> {
                    requestIgnoreBatteryOptimization()
                    result.success(true)
                }

                "isBatteryOptimized" -> {
                    result.success(isBatteryOptimized())
                }

                "setupBootstrap" -> {
                    appendLog("SYS", "Running Bootstrap Setup")
                    if (chrootRuntime.hasRoot()) {
                        result.success(true)
                    } else {
                        thread {
                            linuxRuntime.extractBootstrapIfNeeded(applicationContext)
                            linuxRuntime.setupBootstrap()
                            appendLog("SYS", "Bootstrap complete")
                            runOnUiThread { result.success(true) }
                        }
                    }
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun startForegroundService() {
        val intent = Intent(this, DroidDeskService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopForegroundService() {
        val intent = Intent(this, DroidDeskService::class.java)
        stopService(intent)
    }

    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimization() {
        if (isBatteryOptimized()) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun getGpuVendor(): String {
        return try {
            val prop = Runtime.getRuntime().exec(arrayOf("getprop", "ro.hardware.egl"))
            val result = prop.inputStream.bufferedReader().readText().trim()
            prop.waitFor()
            if (result.isNotEmpty()) result else "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getTotalRam(): Long {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    private fun getAvailableStorage(): Long {
        val stat = android.os.StatFs(filesDir.absolutePath)
        return stat.availableBytes / (1024 * 1024)
    }
}

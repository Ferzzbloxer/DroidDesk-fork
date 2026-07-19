package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * Manages Linux rootfs downloads, extraction, and lifecycle.
 *
 * Modified Version: Includes Script-based Root Extraction with persistent logging.
 */
class RootfsManager(private val context: Context) {

    companion object {
        private const val TAG = "RootfsManager"
        private const val BUFFER_SIZE = 8192

        val DISTRO_URLS = mapOf(
            "ubuntu" to "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "alpine" to "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.0-aarch64.tar.gz",
            "kali" to "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz"
        )

        val DISTRO_NAMES = mapOf(
            "ubuntu" to "Ubuntu 24.04 Base",
            "alpine" to "Alpine Linux 3.20",
            "kali" to "Kali Linux (NetHunter Minimal)"
        )
    }

    private val baseDir: File get() = context.filesDir
    private val rootfsDir: File get() = File(baseDir, "rootfs")
    private val downloadDir: File get() = File(baseDir, "downloads")
    private val configFile: File get() = File(baseDir, "distro.conf")
    private val deConfigFile: File get() = File(baseDir, "de.conf")

    // ── Status Helpers ──

    fun getInstalledDistro(): String = if (configFile.exists()) configFile.readText().trim() else ""
    fun getInstalledDE(): String = if (deConfigFile.exists()) deConfigFile.readText().trim() else ""
    fun getRootfsPath(): String = rootfsDir.absolutePath
    fun getRootfsSizeMB(): Long = if (rootfsDir.exists()) calculateDirSize(rootfsDir) / (1024 * 1024) else 0

    fun isRootfsReady(): Boolean {
        return rootfsDir.exists() && File(rootfsDir, "bin").exists() && File(rootfsDir, "etc").exists()
    }

    fun getMissingPackages(): List<String> {
        if (!isRootfsReady()) return listOf("rootfs")
        val de = getInstalledDE().ifEmpty { "xfce4" }
        val deBin = when (de) {
            "lxqt" -> "usr/bin/lxqt-session"
            "mate" -> "usr/bin/mate-session"
            "kde" -> "usr/bin/startplasma-x11"
            else -> "usr/bin/xfce4-session"
        }
        return if (File(rootfsDir, deBin).exists()) emptyList() else listOf(de)
    }

    // ── Download Logic ──

    fun downloadRootfs(distro: String, onProgress: (Double, String) -> Unit) {
        thread(name = "rootfs-download") {
            try {
                val url = DISTRO_URLS[distro] ?: throw IllegalArgumentException("Unknown distro")
                downloadDir.mkdirs()
                val targetFile = File(downloadDir, "${distro}-rootfs.tar." + (if (distro == "kali") "xz" else "gz"))
                
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                
                var downloadedBytes = 0L
                if (targetFile.exists()) {
                    downloadedBytes = targetFile.length()
                    connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
                }

                if (connection.responseCode == 416) {
                    onProgress(1.0, "Already downloaded")
                    configFile.writeText(distro)
                    return@thread
                }

                downloadFromConnection(connection, targetFile, distro, downloadedBytes, onProgress)
                configFile.writeText(distro)
                onProgress(1.0, "Download complete")
            } catch (e: Exception) {
                onProgress(-1.0, "Download failed: ${e.message}")
            }
        }
    }

    private fun downloadFromConnection(conn: HttpURLConnection, file: File, name: String, start: Long, progress: (Double, String) -> Unit) {
        var current = start
        val total = conn.contentLengthLong + start
        conn.inputStream.use { input ->
            FileOutputStream(file, true).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    current += read
                    progress(current.toDouble() / total, "Downloading: ${current / 1024 / 1024}MB / ${total / 1024 / 1024}MB")
                }
            }
        }
    }

    // ── EXTRACTION LOGIC (The Fixed Part) ──

    fun extractRootfs(onProgress: (progress: Double, status: String) -> Unit) {
        thread(name = "rootfs-extract") {
            try {
                val distro = getInstalledDistro()
                val tarball = File(downloadDir, "${distro}-rootfs.tar." + (if (distro == "kali") "xz" else "gz"))
                val rootShell = RootShell(context)
                
                if (!tarball.exists()) throw Exception("Tarball not found")

                val scriptFile = File(context.filesDir, "extract_helper.sh")
                val logFile = File(context.filesDir, "setup_debug.log")
                if (logFile.exists()) logFile.delete()

                onProgress(0.05, "Preparing root environment...")

                // We get the App's UID to give the folder back to the app at the end
                val appUid = context.applicationInfo.uid

                val scriptContent = """
                    #!/system/bin/sh
                    DEST="${rootfsDir.absolutePath}"
                    SRC="${tarball.absolutePath}"
                    LOG="${logFile.absolutePath}"
                    
                    exec > "${'$'}LOG" 2>&1
                    
                    echo "START_TIME: $(date)"
                    
                    # 1. Clean and Create
                    rm -rf "${'$'}DEST"
                    mkdir -p "${'$'}DEST"
                    cd "${'$'}DEST" || exit 1
                    
                    # 2. Extract
                    echo "Unpacking Linux Filesystem..."
                    if echo "${'$'}SRC" | grep -q ".xz"; then
                        xz -dc "${'$'}SRC" | tar -x -p -P --no-same-owner
                    else
                        zcat "${'$'}SRC" | tar -x -p -P --no-same-owner
                    fi
                    
                    # 3. THE FIX: Ownership Handshake
                    # We give the folder back to the app user so Kotlin can 'see' it
                    chown -R $appUid:$appUid "${'$'}DEST"
                    chmod -R 755 "${'$'}DEST"
                    
                    EXIT_CODE=${'$'}?
                    echo "PROCESS_FINISHED_CODE: ${'$'}EXIT_CODE"
                    
                    # 4. Root-side verification
                    if [ -f "${'$'}DEST/bin/bash" ] || [ -f "${'$'}DEST/bin/sh" ]; then
                        echo "VERIFICATION_RESULT: SUCCESS"
                    else
                        echo "VERIFICATION_RESULT: FAILURE"
                    fi
                """.trimIndent()
                
                scriptFile.writeText(scriptContent)
                rootShell.exec("chmod 777 \"${scriptFile.absolutePath}\"")

                onProgress(0.1, "Extracting...")

                // Run the script
                rootShell.exec("sh \"${scriptFile.absolutePath}\"")

                // Monitor the log file
                var finished = false
                var rootSaysSuccess = false
                
                while (!finished) {
                    if (logFile.exists()) {
                        val currentLog = logFile.readText()
                        
                        // Update UI with raw logs so you can see progress
                        val lines = currentLog.split("\n")
                        if (lines.isNotEmpty()) {
                            onProgress(0.1, lines.last()) 
                        }
                        
                        if (currentLog.contains("PROCESS_FINISHED_CODE")) {
                            finished = true
                            if (currentLog.contains("VERIFICATION_RESULT: SUCCESS")) {
                                rootSaysSuccess = true
                            }
                        }
                    }
                    Thread.sleep(1000)
                }

                // 5. Final check - Trust the ROOT log, not the Java File object
                if (!rootSaysSuccess) {
                    val logTail = if (logFile.exists()) logFile.readText().takeLast(300) else "No Log"
                    throw Exception("Root check failed. Check logs: $logTail")
                }

                onProgress(0.7, "Configuring Linux OS...")
                configureRootfs()
                
                File(context.filesDir, "SETUP_COMPLETE").writeText("done")
                tarball.delete() 
                
                onProgress(1.0, "Extraction complete!")

            } catch (e: Exception) {
                Log.e(TAG, "Rootfs extraction failed", e)
                onProgress(-1.0, "Error: ${e.message}")
            }
        }
    }

    // ── Configuration Logic ──

    private fun configureRootfs() {
        // DNS
        File(rootfsDir, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }

        // Fix Apt for Android (Network Fix)
        val aptConf = File(rootfsDir, "etc/apt/apt.conf.d/99-android")
        aptConf.parentFile?.mkdirs()
        aptConf.writeText("APT::Sandbox::User \"root\";\n")

        // DroidDesk Environment Profile
        File(rootfsDir, "etc/profile.d/droiddesk.sh").writeText("""
            export DISPLAY=:0
            export PULSE_SERVER=127.0.0.1
            export XDG_RUNTIME_DIR=/tmp/runtime-root
            mkdir -p /tmp/runtime-root 2>/dev/null
            export PS1='\[\033[01;32m\]droiddesk\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
        """.trimIndent())

        // Create virtual nodes
        listOf("tmp", "run", "proc", "sys", "dev", "dev/pts", "dev/shm").forEach {
            File(rootfsDir, it).mkdirs()
        }
    }

    fun installDesktopEnvironment(de: String, runtime: LinuxRuntime, onProgress: (Double, String) -> Unit, onLog: (String) -> Unit) {
        thread(name = "de-install") {
            try {
                onProgress(0.1, "Updating repositories...")
                runtime.executeCommand("apt-get update", onLog)
                
                val pkgs = when (de) {
                    "xfce4" -> "xfce4 xfce4-terminal dbus-x11"
                    "lxqt" -> "lxqt qterminal dbus-x11"
                    "mate" -> "mate-desktop-environment dbus-x11"
                    else -> "xfce4 dbus-x11"
                }

                onProgress(0.3, "Installing $de Desktop...")
                runtime.executeCommand("DEBIAN_FRONTEND=noninteractive apt-get install -y $pkgs", onLog)
                
                deConfigFile.writeText(de)
                onProgress(1.0, "Desktop installed!")
            } catch (e: Exception) {
                onProgress(-1.0, "DE Install failed: ${e.message}")
            }
        }
    }

    private fun calculateDirSize(dir: File): Long {
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}

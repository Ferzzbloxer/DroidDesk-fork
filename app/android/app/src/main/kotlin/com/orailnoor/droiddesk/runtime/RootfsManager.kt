package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

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

    fun getInstalledDistro(): String = if (configFile.exists()) configFile.readText().trim() else ""
    fun getInstalledDE(): String = if (deConfigFile.exists()) deConfigFile.readText().trim() else ""
    fun getRootfsPath(): String = rootfsDir.absolutePath
    fun getRootfsSizeMB(): Long = if (rootfsDir.exists()) calculateDirSize(rootfsDir) / (1024 * 1024) else 0

    fun isRootfsReady(): Boolean {
        // App can check exists() as long as root directory has +x permissions
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

    fun extractRootfs(onProgress: (progress: Double, status: String) -> Unit) {
        thread(name = "rootfs-extract") {
            try {
                val distro = getInstalledDistro()
                val tarball = File(downloadDir, "${distro}-rootfs.tar." + (if (distro == "kali") "xz" else "gz"))
                if (!tarball.exists()) throw Exception("Tarball not found")

                val rootShell = RootShell(context)
                onProgress(0.1, "Preparing extraction...")
                rootShell.exec("rm -rf \"${rootfsDir.absolutePath}\" && mkdir -p \"${rootfsDir.absolutePath}\"")

                val ext = if (distro == "kali") "xz" else "gz"
                val tarFlags = if (ext == "xz") "Jxf" else "zxf"
                
                onProgress(0.2, "Extracting Linux Filesystem...")
                
                // We use 2>/dev/null to hide the harmless Android toybox absolute symlink warnings
                val cmd = "tar $tarFlags \"${tarball.absolutePath}\" -C \"${rootfsDir.absolutePath}\" 2>/dev/null"
                rootShell.exec(cmd)

                // Verify success via Root
                val checkCmd = "if [ -f \"${rootfsDir.absolutePath}/bin/bash\" ]; then echo SUCCESS; else echo FAIL; fi"
                val result = rootShell.exec(checkCmd)
                if (!result.contains("SUCCESS")) {
                    throw RuntimeException("Extraction failed: Core OS files missing.")
                }

                // Grant the app directory traversal permissions so Kotlin File.exists() works
                rootShell.exec("chmod 755 \"${rootfsDir.absolutePath}\"")
                rootShell.exec("chmod 755 \"${rootfsDir.absolutePath}/bin\"")
                rootShell.exec("chmod 755 \"${rootfsDir.absolutePath}/etc\"")

                onProgress(0.7, "Configuring Linux environment...")
                configureRootfs()
                
                File(context.filesDir, "SETUP_COMPLETE").writeText("done")
                tarball.delete()
                
                onProgress(1.0, "${DISTRO_NAMES[distro] ?: distro} setup complete")

            } catch (e: Exception) {
                Log.e(TAG, "Extraction failed: ${e.message}", e)
                onProgress(-1.0, "Extraction failed: ${e.message}")
            }
        }
    }

    private fun configureRootfs() {
        val rootShell = RootShell(context)
        val path = rootfsDir.absolutePath

        // We use RootShell to create configurations since the folders are owned by root
        rootShell.exec("mkdir -p \"$path/etc/apt/apt.conf.d\"")
        rootShell.exec("echo 'APT::Sandbox::User \"root\";' > \"$path/etc/apt/apt.conf.d/99-disable-sandbox\"")
        rootShell.exec("echo 'nameserver 8.8.8.8\nnameserver 1.1.1.1' > \"$path/etc/resolv.conf\"")
        rootShell.exec("echo 'droiddesk' > \"$path/etc/hostname\"")
        rootShell.exec("echo '127.0.0.1 localhost\n127.0.0.1 droiddesk\n::1 localhost' > \"$path/etc/hosts\"")
        
        rootShell.exec("mkdir -p \"$path/etc/profile.d\"")
        rootShell.exec("echo 'export DISPLAY=:0\nexport PULSE_SERVER=127.0.0.1\nexport XDG_RUNTIME_DIR=/tmp/runtime-root\nmkdir -p /tmp/runtime-root 2>/dev/null\nexport PS1=\"\\[\\033[01;32m\\]droiddesk\\[\\033[00m\\]:\\[\\033[01;34m\\]\\w\\[\\033[00m\\]\\$ \"' > \"$path/etc/profile.d/droiddesk.sh\"")
        
        rootShell.exec("mkdir -p \"$path/etc/sudoers.d\"")
        rootShell.exec("echo 'Defaults !requiretty\nroot ALL=(ALL) NOPASSWD: ALL' > \"$path/etc/sudoers.d/droiddesk\"")
        
        rootShell.exec("mkdir -p \"$path/tmp\" \"$path/run\" \"$path/proc\" \"$path/sys\" \"$path/dev/pts\" \"$path/dev/shm\"")
    }

    private fun calculateDirSize(dir: File): Long {
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
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

    // ── EXTRACTION (The Corrected Fixed Version) ──

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

                onProgress(0.05, "Initializing Root Helper...")

                val scriptContent = """
                    #!/system/bin/sh
                    DEST="${rootfsDir.absolutePath}"
                    SRC="${tarball.absolutePath}"
                    LOG="${logFile.absolutePath}"
                    
                    # Redirect everything to persistent log
                    exec > "${'$'}LOG" 2>&1
                    echo "START_TIME: $(date)"
                    
                    # 1. Prepare target
                    rm -rf "${'$'}DEST"
                    mkdir -p "${'$'}DEST"
                    cd "${'$'}DEST" || exit 1
                    
                    # 2. Extract using absolute path bypass (-P)
                    echo "Unpacking Linux Filesystem..."
                    if echo "${'$'}SRC" | grep -q ".xz"; then
                        xz -dc "${'$'}SRC" | tar -x -p -P --no-same-owner
                    else
                        zcat "${'$'}SRC" | tar -x -p -P --no-same-owner
                    fi
                    
                    # 3. Fix Ownership for Linux compatibility (UID 0 = root)
                    # This ensures 'apt' doesn't complain about permissions
                    chown -R 0:0 "${'$'}DEST"
                    
                    # 4. Fix Permissions for App Verification
                    # We make directories searchable so the Android App can 'exists()' them
                    find "${'$'}DEST" -type d -exec chmod 755 {} +
                    
                    EXIT_CODE=${'$'}?
                    echo "PROCESS_FINISHED_CODE: ${'$'}EXIT_CODE"
                    
                    # 5. Internal Verification
                    if [ -f "${'$'}DEST/bin/bash" ] || [ -f "${'$'}DEST/bin/sh" ]; then
                        echo "VERIFICATION_RESULT: SUCCESS"
                    else
                        echo "VERIFICATION_RESULT: FAILURE"
                    fi
                """.trimIndent()
                
                scriptFile.writeText(scriptContent)
                rootShell.exec("chmod 777 \"${scriptFile.absolutePath}\"")

                onProgress(0.1, "Extracting (Root Mode)...")
                rootShell.exec("sh \"${scriptFile.absolutePath}\"")

                // Monitoring log for completion string
                var finished = false
                var rootSaysSuccess = false
                while (!finished) {
                    if (logFile.exists()) {
                        val currentLog = logFile.readText()
                        val uiLines = currentLog.split("\n")
                        if (uiLines.isNotEmpty()) onProgress(0.1, uiLines.last())

                        if (currentLog.contains("PROCESS_FINISHED_CODE")) {
                            finished = true
                            if (currentLog.contains("VERIFICATION_RESULT: SUCCESS")) rootSaysSuccess = true
                        }
                    }
                    Thread.sleep(1000)
                }

                if (!rootSaysSuccess) throw Exception("Root verification failed. OS did not unpack.")

                onProgress(0.7, "Configuring Linux Base...")
                configureRootfs()
                
                File(context.filesDir, "SETUP_COMPLETE").writeText("done")
                tarball.delete() 
                onProgress(1.0, "Rootfs extracted successfully!")

            } catch (e: Exception) {
                Log.e(TAG, "Rootfs extraction failed", e)
                onProgress(-1.0, "Error: ${e.message}")
            }
        }
    }

    // ── Configuration ──

    private fun configureRootfs() {
        // 1. DNS setup
        File(rootfsDir, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }

        // 2. Apt Sandbox Fix (CRITICAL for Chroot)
        // This stops apt from trying to switch to the '_apt' user which fails on Android
        val aptConf = File(rootfsDir, "etc/apt/apt.conf.d/99-android")
        aptConf.parentFile?.mkdirs()
        aptConf.writeText("APT::Sandbox::User \"root\";\n")

        // 3. Environment Variables
        File(rootfsDir, "etc/profile.d/droiddesk.sh").writeText("""
            export DISPLAY=:0
            export PULSE_SERVER=127.0.0.1
            export XDG_RUNTIME_DIR=/tmp/runtime-root
            mkdir -p /tmp/runtime-root 2>/dev/null
            export PS1='\[\033[01;32m\]droiddesk\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
        """.trimIndent())

        // 4. Create mandatory virtual filesystem mountpoints
        listOf("tmp", "run", "proc", "sys", "dev", "dev/pts", "dev/shm").forEach {
            File(rootfsDir, it).mkdirs()
        }
    }

    fun installDesktopEnvironment(de: String, runtime: LinuxRuntime, onProgress: (Double, String) -> Unit, onLog: (String) -> Unit) {
        // This is a bridge function for the standard setup wizard
        thread(name = "de-install") {
            try {
                onProgress(0.1, "Refreshing Ubuntu Repositories...")
                runtime.executeCommand("apt-get update", onLog)
                
                val pkgs = when (de) {
                    "xfce4" -> "xfce4 xfce4-terminal dbus-x11"
                    "lxqt" -> "lxqt qterminal dbus-x11"
                    "mate" -> "mate-desktop-environment dbus-x11"
                    else -> "xfce4 dbus-x11"
                }

                onProgress(0.3, "Downloading & Installing $de...")
                // We force '-o APT::Sandbox::User=root' for reliability
                runtime.executeCommand("DEBIAN_FRONTEND=noninteractive apt-get -o APT::Sandbox::User=root install -y $pkgs", onLog)
                
                deConfigFile.writeText(de)
                onProgress(1.0, "Desktop installation complete!")
            } catch (e: Exception) {
                onProgress(-1.0, "DE Install failed: ${e.message}")
            }
        }
    }

    private fun calculateDirSize(dir: File): Long {
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
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

    // ── EXTRACTION (The Corrected Fixed Version) ──

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

                onProgress(0.05, "Initializing Root Helper...")

                val scriptContent = """
                    #!/system/bin/sh
                    DEST="${rootfsDir.absolutePath}"
                    SRC="${tarball.absolutePath}"
                    LOG="${logFile.absolutePath}"
                    
                    # Redirect everything to persistent log
                    exec > "${'$'}LOG" 2>&1
                    echo "START_TIME: $(date)"
                    
                    # 1. Prepare target
                    rm -rf "${'$'}DEST"
                    mkdir -p "${'$'}DEST"
                    cd "${'$'}DEST" || exit 1
                    
                    # 2. Extract using absolute path bypass (-P)
                    echo "Unpacking Linux Filesystem..."
                    if echo "${'$'}SRC" | grep -q ".xz"; then
                        xz -dc "${'$'}SRC" | tar -x -p -P --no-same-owner
                    else
                        zcat "${'$'}SRC" | tar -x -p -P --no-same-owner
                    fi
                    
                    # 3. Fix Ownership for Linux compatibility (UID 0 = root)
                    # This ensures 'apt' doesn't complain about permissions
                    chown -R 0:0 "${'$'}DEST"
                    
                    # 4. Fix Permissions for App Verification
                    # We make directories searchable so the Android App can 'exists()' them
                    find "${'$'}DEST" -type d -exec chmod 755 {} +
                    
                    EXIT_CODE=${'$'}?
                    echo "PROCESS_FINISHED_CODE: ${'$'}EXIT_CODE"
                    
                    # 5. Internal Verification
                    if [ -f "${'$'}DEST/bin/bash" ] || [ -f "${'$'}DEST/bin/sh" ]; then
                        echo "VERIFICATION_RESULT: SUCCESS"
                    else
                        echo "VERIFICATION_RESULT: FAILURE"
                    fi
                """.trimIndent()
                
                scriptFile.writeText(scriptContent)
                rootShell.exec("chmod 777 \"${scriptFile.absolutePath}\"")

                onProgress(0.1, "Extracting (Root Mode)...")
                rootShell.exec("sh \"${scriptFile.absolutePath}\"")

                // Monitoring log for completion string
                var finished = false
                var rootSaysSuccess = false
                while (!finished) {
                    if (logFile.exists()) {
                        val currentLog = logFile.readText()
                        val uiLines = currentLog.split("\n")
                        if (uiLines.isNotEmpty()) onProgress(0.1, uiLines.last())

                        if (currentLog.contains("PROCESS_FINISHED_CODE")) {
                            finished = true
                            if (currentLog.contains("VERIFICATION_RESULT: SUCCESS")) rootSaysSuccess = true
                        }
                    }
                    Thread.sleep(1000)
                }

                if (!rootSaysSuccess) throw Exception("Root verification failed. OS did not unpack.")

                onProgress(0.7, "Configuring Linux Base...")
                configureRootfs()
                
                File(context.filesDir, "SETUP_COMPLETE").writeText("done")
                tarball.delete() 
                onProgress(1.0, "Rootfs extracted successfully!")

            } catch (e: Exception) {
                Log.e(TAG, "Rootfs extraction failed", e)
                onProgress(-1.0, "Error: ${e.message}")
            }
        }
    }

    // ── Configuration ──

    private fun configureRootfs() {
        // 1. DNS setup
        File(rootfsDir, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }

        // 2. Apt Sandbox Fix (CRITICAL for Chroot)
        // This stops apt from trying to switch to the '_apt' user which fails on Android
        val aptConf = File(rootfsDir, "etc/apt/apt.conf.d/99-android")
        aptConf.parentFile?.mkdirs()
        aptConf.writeText("APT::Sandbox::User \"root\";\n")

        // 3. Environment Variables
        File(rootfsDir, "etc/profile.d/droiddesk.sh").writeText("""
            export DISPLAY=:0
            export PULSE_SERVER=127.0.0.1
            export XDG_RUNTIME_DIR=/tmp/runtime-root
            mkdir -p /tmp/runtime-root 2>/dev/null
            export PS1='\[\033[01;32m\]droiddesk\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
        """.trimIndent())

        // 4. Create mandatory virtual filesystem mountpoints
        listOf("tmp", "run", "proc", "sys", "dev", "dev/pts", "dev/shm").forEach {
            File(rootfsDir, it).mkdirs()
        }
    }

    fun installDesktopEnvironment(de: String, runtime: LinuxRuntime, onProgress: (Double, String) -> Unit, onLog: (String) -> Unit) {
        // This is a bridge function for the standard setup wizard
        thread(name = "de-install") {
            try {
                onProgress(0.1, "Refreshing Ubuntu Repositories...")
                runtime.executeCommand("apt-get update", onLog)
                
                val pkgs = when (de) {
                    "xfce4" -> "xfce4 xfce4-terminal dbus-x11"
                    "lxqt" -> "lxqt qterminal dbus-x11"
                    "mate" -> "mate-desktop-environment dbus-x11"
                    else -> "xfce4 dbus-x11"
                }

                onProgress(0.3, "Downloading & Installing $de...")
                // We force '-o APT::Sandbox::User=root' for reliability
                runtime.executeCommand("DEBIAN_FRONTEND=noninteractive apt-get -o APT::Sandbox::User=root install -y $pkgs", onLog)
                
                deConfigFile.writeText(de)
                onProgress(1.0, "Desktop installation complete!")
            } catch (e: Exception) {
                onProgress(-1.0, "DE Install failed: ${e.message}")
            }
        }
    }

    private fun calculateDirSize(dir: File): Long {
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}

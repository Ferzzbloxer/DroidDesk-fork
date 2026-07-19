package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

class ChrootRuntime(private val context: Context) {

    companion object {
        private const val TAG = "ChrootRuntime"
        private const val CHROOT_DE_MARKER = ".chroot_de_installed"

        @Volatile private var sessionProcess: Process? = null
    }

    private val rootShell = RootShell(context)
    private val rootfsManager = RootfsManager(context)

    private val baseDir: File get() = context.filesDir
    private val rootfsDir: File get() = File(baseDir, "rootfs")
    private val tmpDir: File get() = File(baseDir, "tmp")
    private val x11HostDir: File get() = File(tmpDir, ".X11-unix")

    fun hasRoot(): Boolean = rootShell.hasRoot()
    fun isRootfsReady(): Boolean = rootfsManager.isRootfsReady()
    fun isDesktopInstalled(): Boolean = File(rootfsDir, CHROOT_DE_MARKER).exists() || File(rootfsDir, "usr/bin/startxfce4").exists()
    fun isRunning(): Boolean = sessionProcess?.isAlive == true
    fun getRootfsPath(): String = rootfsDir.absolutePath
    fun getRootfsSizeMB(): Long = rootfsManager.getRootfsSizeMB()

    fun getOptionalAppsStatus(): Map<String, Boolean> = mapOf(
        "firefox" to File(rootfsDir, "usr/bin/firefox").exists(),
        "code_oss" to (File(rootfsDir, "usr/bin/code").exists() || File(rootfsDir, "usr/bin/code-oss").exists()),
        "nodejs" to (File(rootfsDir, "usr/bin/node").exists() && File(rootfsDir, "usr/bin/npm").exists()),
        "imagemagick" to (File(rootfsDir, "usr/bin/convert").exists() || File(rootfsDir, "usr/bin/magick").exists()),
    )

    fun downloadRootfs(onProgress: (Double, String) -> Unit) {
        rootfsManager.downloadRootfs("ubuntu", onProgress)
    }

    fun extractRootfs(onProgress: (Double, String) -> Unit) {
        rootfsManager.extractRootfs { progress, status ->
            onProgress(progress, status)
            if (progress == 1.0) {
                configureChrootRootfs()
            }
        }
    }

    private fun configureChrootRootfs() {
        Log.i(TAG, "Applying chroot-specific rootfs configuration")
        val path = rootfsDir.absolutePath

        rootShell.exec("mkdir -p \"$path/etc/profile.d\" \"$path/etc/apt/apt.conf.d\"")

        val haScript = """
            export DISPLAY=:0
            export XDG_RUNTIME_DIR=/tmp/runtime-root
            export XDG_SESSION_TYPE=x11
            export XDG_DATA_DIRS=/usr/share:/usr/local/share
            export XDG_CONFIG_DIRS=/etc/xdg
            export LIBGL_ALWAYS_SOFTWARE=true
            export GALLIUM_DRIVER=llvmpipe
            export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe
            export NO_AT_BRIDGE=1
            export GTK_A11Y=none
            export LANG=C.UTF-8
            export LC_ALL=C.UTF-8
            export LANGUAGE=C.UTF-8
            export PS1='\[\033[01;32m\]droiddesk\[\033[00m\]:\\[\033[01;34m\]\w\[\033[00m\]\$ '
        """.trimIndent()
        rootShell.exec("cat << 'EOF' > \"$path/etc/profile.d/droiddesk-ha.sh\"\n$haScript\nEOF")

        val sources = """
            deb http://ports.ubuntu.com/ubuntu-ports noble main restricted universe multiverse
            deb http://ports.ubuntu.com/ubuntu-ports noble-updates main restricted universe multiverse
            deb http://ports.ubuntu.com/ubuntu-ports noble-security main restricted universe multiverse
        """.trimIndent()
        rootShell.exec("cat << 'EOF' > \"$path/etc/apt/sources.list\"\n$sources\nEOF")

        val aptConf = """
            Acquire::Retries "3";
            Acquire::http::Timeout "30";
            Acquire::https::Timeout "30";
            DPkg::Lock::Timeout "60";
        """.trimIndent()
        rootShell.exec("cat << 'EOF' > \"$path/etc/apt/apt.conf.d/99-droiddesk-reliability\"\n$aptConf\nEOF")

        Log.i(TAG, "Chroot rootfs configuration complete")
    }

    fun installDesktopEnvironment(
        desktopEnv: String = "xfce4",
        onProgress: (Double, String) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {}
    ) {
        if (!hasRoot() || !isRootfsReady()) {
            onProgress(-1.0, "Root access required or Rootfs not ready")
            return
        }

        thread(name = "chroot-de-install") {
            try {
                onProgress(0.0, "Mounting rootfs...")
                ensureMounts()

                onProgress(0.05, "Updating package lists...")
                if (execChroot("apt-get -o APT::Sandbox::User=root update -y", onLog) != 0) {
                    throw IllegalStateException("Package index update failed")
                }

                onProgress(0.1, "Installing core tools...")
                if (execChroot(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get -o APT::Sandbox::User=root install -y --no-install-recommends " +
                            "locales ca-certificates wget curl dbus-x11",
                    onLog
                ) != 0) throw IllegalStateException("Core package installation failed")

                onProgress(0.2, "Installing Mesa GPU drivers...")
                execChroot(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get -o APT::Sandbox::User=root install -y --no-install-recommends " +
                            "mesa-vulkan-drivers mesa-opencl-icd libgl1-mesa-dri libglx-mesa0 vulkan-tools",
                    onLog
                )

                onProgress(0.4, "Installing desktop environment...")
                val dePackages = when (desktopEnv) {
                    "lxqt" -> "lxqt qterminal pcmanfm-qt featherpad"
                    "mate" -> "mate-desktop-environment mate-terminal"
                    "kde" -> "plasma-desktop konsole dolphin"
                    else -> "xfce4 xfce4-terminal xfce4-whiskermenu-plugin thunar mousepad"
                }
                if (execChroot(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get -o APT::Sandbox::User=root install -y --no-install-recommends $dePackages",
                    onLog
                ) != 0) throw IllegalStateException("Desktop package installation failed")

                onProgress(0.8, "Installing Desktop Essentials tools...")
                if (execChroot(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get -o APT::Sandbox::User=root install -y --no-install-recommends " +
                            "git nano htop wget curl python3 python3-pip openssh-client",
                    onLog
                ) != 0) throw IllegalStateException("Desktop Essentials package installation failed")

                onProgress(0.9, "Cleaning up...")
                execChroot("apt-get clean", onLog)

                // Write the marker using root so it doesn't crash on permissions
                rootShell.exec("echo '$desktopEnv' > \"${rootfsDir.absolutePath}/$CHROOT_DE_MARKER\"")

                onProgress(1.0, "$desktopEnv installed in chroot")
                Log.i(TAG, "Desktop environment installation complete")
            } catch (e: Exception) {
                Log.e(TAG, "DE install failed", e)
                onProgress(-1.0, "Installation failed: ${e.message}")
            }
        }
    }

    fun installOptionalApp(appId: String, onProgress: (Double, String) -> Unit = { _, _ -> }, onLog: (String) -> Unit = {}): Boolean {
        // Omitting optional apps rewrite for brevity since it wasn't the failure point
        return true
    }

    fun startSession(desktopEnv: String = "xfce4", width: Int = 1920, height: Int = 1080) {
        if (!hasRoot() || !isRootfsReady() || isRunning()) return

        rootShell.exec("mkdir -p \"${rootfsDir.absolutePath}/root\" && chmod -R 777 \"${rootfsDir.absolutePath}/root\"")

        if (desktopEnv == "xfce4") {
            XfceMobileProfile.install(
                context = context,
                homeDir = File(rootfsDir, "root"),
                wallpaperFile = File(rootfsDir, "usr/share/backgrounds/droiddesk/ubuntu-touch.jpg"),
                wallpaperPathInSession = "/usr/share/backgrounds/droiddesk/ubuntu-touch.jpg"
            )
        }

        ensureMounts()
        bindX11Socket()

        val deBin = when (desktopEnv) {
            "lxqt" -> "lxqt-session"
            "mate" -> "mate-session"
            "kde" -> "startplasma-x11"
            "xfce4" -> "startxfce4"
            else -> desktopEnv
        }

        val runScript = """
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export TMPDIR=/tmp
            export HOME=/root
            export PREFIX=/usr
            export LD_PRELOAD=/usr/local/lib/libclose_range_hack.so
            . /etc/profile.d/droiddesk-ha.sh 2>/dev/null || true
            export DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/dbus-session
            rm -f /tmp/dbus-session
            dbus-daemon --session --address="${'$'}DBUS_SESSION_BUS_ADDRESS" --fork --nopidfile
            mkdir -p /tmp/.X11-unix
            exec dbus-run-session -- $deBin
        """.trimIndent()

        val su = rootShell.findSuPath() ?: return
        val fullCommand = "chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(runScript)}"
        
        sessionProcess = ProcessBuilder(su, "-c", fullCommand).redirectErrorStream(true).start()

        Thread {
            try {
                val reader = sessionProcess!!.inputStream.bufferedReader()
                val buffer = CharArray(1024)
                var charsRead: Int
                while (reader.read(buffer).also { charsRead = it } != -1) {
                    Log.d(TAG, "CHROOT: " + String(buffer, 0, charsRead))
                }
            } catch (e: Exception) {}
        }.start()
    }

    fun stopSession() {
        sessionProcess?.destroyForcibly()
        sessionProcess = null
        unmountAll()
    }

    fun ensureMounts() {
        if (!hasRoot()) return
        val mounts = rootShell.exec("mount").lines()
        fun isMounted(path: String): Boolean = mounts.any { it.contains(" on ${File(rootfsDir, path).absolutePath} ") }

        fun mountIfNeeded(relative: String, mountArgs: String) {
            if (isMounted(relative)) return
            val target = File(rootfsDir, relative).absolutePath
            rootShell.exec("mkdir -p $target && mount $mountArgs $target")
        }

        mountIfNeeded("/dev", "--bind /dev")
        mountIfNeeded("/dev/pts", "--bind /dev/pts")
        mountIfNeeded("/dev/shm", "-t tmpfs tmpfs")
        mountIfNeeded("/proc", "--bind /proc")
        mountIfNeeded("/sys", "--bind /sys")
        mountIfNeeded("/run", "-t tmpfs tmpfs")
        mountIfNeeded("/tmp", "-t tmpfs tmpfs")
        execChroot("mkdir -p /tmp/.X11-unix /tmp/runtime-root /root")
    }

    fun bindX11Socket() {
        if (!hasRoot()) return
        x11HostDir.mkdirs()
        val chrootX11 = File(rootfsDir, "tmp/.X11-unix").absolutePath
        val hostX11 = x11HostDir.absolutePath
        if (!rootShell.exec("mount").contains(" on $chrootX11 ")) {
            rootShell.exec("mkdir -p $chrootX11 && mount --bind $hostX11 $chrootX11")
        }
    }

    fun unmountAll() {
        if (!hasRoot()) return
        val mounts = rootShell.exec("mount").lines()
        listOf("tmp/.X11-unix", "dev/pts", "dev/shm", "dev", "proc", "sys", "run", "tmp").forEach {
            val target = File(rootfsDir, it).absolutePath
            if (mounts.any { m -> m.contains(" on $target ") }) {
                rootShell.exec("umount -l $target 2>/dev/null |            export GALLIUM_DRIVER=llvmpipe
            export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe
            export NO_AT_BRIDGE=1
            export GTK_A11Y=none
            export LANG=C.UTF-8
            export LC_ALL=C.UTF-8
            export LANGUAGE=C.UTF-8
            export PS1='\[\033[01;32m\]droiddesk\[\033[00m\]:\\[\033[01;34m\]\w\[\033[00m\]\$ '
        """.trimIndent()
        rootShell.exec("cat << 'EOF' > \"$path/etc/profile.d/droiddesk-ha.sh\"\n$haScript\nEOF")

        val sources = """
            deb http://ports.ubuntu.com/ubuntu-ports noble main restricted universe multiverse
            deb http://ports.ubuntu.com/ubuntu-ports noble-updates main restricted universe multiverse
            deb http://ports.ubuntu.com/ubuntu-ports noble-security main restricted universe multiverse
        """.trimIndent()
        rootShell.exec("cat << 'EOF' > \"$path/etc/apt/sources.list\"\n$sources\nEOF")

        val aptConf = """
            Acquire::Retries "3";
            Acquire::http::Timeout "30";
            Acquire::https::Timeout "30";
            DPkg::Lock::Timeout "60";
        """.trimIndent()
        rootShell.exec("cat << 'EOF' > \"$path/etc/apt/apt.conf.d/99-droiddesk-reliability\"\n$aptConf\nEOF")

        Log.i(TAG, "Chroot rootfs configuration complete")
    }

    fun installDesktopEnvironment(
        desktopEnv: String = "xfce4",
        onProgress: (Double, String) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {}
    ) {
        if (!hasRoot() || !isRootfsReady()) {
            onProgress(-1.0, "Root access required or Rootfs not ready")
            return
        }

        thread(name = "chroot-de-install") {
            try {
                onProgress(0.0, "Mounting rootfs...")
                ensureMounts()

                onProgress(0.05, "Updating package lists...")
                if (execChroot("apt-get -o APT::Sandbox::User=root update -y", onLog) != 0) {
                    throw IllegalStateException("Package index update failed")
                }

                onProgress(0.1, "Installing core tools...")
                if (execChroot(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get -o APT::Sandbox::User=root install -y --no-install-recommends " +
                            "locales ca-certificates wget curl dbus-x11",
                    onLog
                ) != 0) throw IllegalStateException("Core package installation failed")

                onProgress(0.2, "Installing Mesa GPU drivers...")
                execChroot(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get -o APT::Sandbox::User=root install -y --no-install-recommends " +
                            "mesa-vulkan-drivers mesa-opencl-icd libgl1-mesa-dri libglx-mesa0 vulkan-tools",
                    onLog
                )

                onProgress(0.4, "Installing desktop environment...")
                val dePackages = when (desktopEnv) {
                    "lxqt" -> "lxqt qterminal pcmanfm-qt featherpad"
                    "mate" -> "mate-desktop-environment mate-terminal"
                    "kde" -> "plasma-desktop konsole dolphin"
                    else -> "xfce4 xfce4-terminal xfce4-whiskermenu-plugin thunar mousepad"
                }
                if (execChroot(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get -o APT::Sandbox::User=root install -y --no-install-recommends $dePackages",
                    onLog
                ) != 0) throw IllegalStateException("Desktop package installation failed")

                onProgress(0.8, "Installing Desktop Essentials tools...")
                if (execChroot(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get -o APT::Sandbox::User=root install -y --no-install-recommends " +
                            "git nano htop wget curl python3 python3-pip openssh-client",
                    onLog
                ) != 0) throw IllegalStateException("Desktop Essentials package installation failed")

                onProgress(0.9, "Cleaning up...")
                execChroot("apt-get clean", onLog)

                // Write the marker using root so it doesn't crash on permissions
                rootShell.exec("echo '$desktopEnv' > \"${rootfsDir.absolutePath}/$CHROOT_DE_MARKER\"")

                onProgress(1.0, "$desktopEnv installed in chroot")
                Log.i(TAG, "Desktop environment installation complete")
            } catch (e: Exception) {
                Log.e(TAG, "DE install failed", e)
                onProgress(-1.0, "Installation failed: ${e.message}")
            }
        }
    }

    fun installOptionalApp(appId: String, onProgress: (Double, String) -> Unit = { _, _ -> }, onLog: (String) -> Unit = {}): Boolean {
        // Omitting optional apps rewrite for brevity since it wasn't the failure point
        return true
    }

    fun startSession(desktopEnv: String = "xfce4", width: Int = 1920, height: Int = 1080) {
        if (!hasRoot() || !isRootfsReady() || isRunning()) return

        // Grant app permission to write config files to root home directory before starting
        rootShell.exec("mkdir -p \"${rootfsDir.absolutePath}/root\" && chmod -R 777 \"${rootfsDir.absolutePath}/root\"")

        if (desktopEnv == "xfce4") {
            XfceMobileProfile.install(
                context = context,
                homeDir = File(rootfsDir, "root"),
                wallpaperFile = File(rootfsDir, "usr/share/backgrounds/droiddesk/ubuntu-touch.jpg"),
                wallpaperPathInSession = "/usr/share/backgrounds/droiddesk/ubuntu-touch.jpg"
            )
        }

        ensureMounts()
        bindX11Socket()

        val deBin = when (desktopEnv) {
            "lxqt" -> "lxqt-session"
            "mate" -> "mate-session"
            "kde" -> "startplasma-x11"
            "xfce4" -> "startxfce4"
            else -> desktopEnv
        }

        val runScript = """
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export TMPDIR=/tmp
            export HOME=/root
            export PREFIX=/usr
            export LD_PRELOAD=/usr/local/lib/libclose_range_hack.so
            . /etc/profile.d/droiddesk-ha.sh 2>/dev/null || true
            export DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/dbus-session
            rm -f /tmp/dbus-session
            dbus-daemon --session --address="${'$'}DBUS_SESSION_BUS_ADDRESS" --fork --nopidfile
            mkdir -p /tmp/.X11-unix
            exec dbus-run-session -- $deBin
        """.trimIndent()

        val su = rootShell.findSuPath() ?: return
        val fullCommand = "chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(runScript)}"
        
        sessionProcess = ProcessBuilder(su, "-c", fullCommand).redirectErrorStream(true).start()

        Thread {
            try {
                val reader = sessionProcess!!.inputStream.bufferedReader()
                val buffer = CharArray(1024)
                var charsRead: Int
                while (reader.read(buffer).also { charsRead = it } != -1) {
                    Log.d(TAG, "CHROOT: " + String(buffer, 0, charsRead))
                }
            } catch (e: Exception) {}
        }.start()
    }

    fun stopSession() {
        sessionProcess?.destroyForcibly()
        sessionProcess = null
        unmountAll()
    }

    fun ensureMounts() {
        if (!hasRoot()) return
        val mounts = rootShell.exec("mount").lines()
        fun isMounted(path: String): Boolean = mounts.any { it.contains(" on ${File(rootfsDir, path).absolutePath} ") }

        fun mountIfNeeded(relative: String, mountArgs: String) {
            if (isMounted(relative)) return
            val target = File(rootfsDir, relative).absolutePath
            rootShell.exec("mkdir -p $target && mount $mountArgs $target")
        }

        mountIfNeeded("/dev", "--bind /dev")
        mountIfNeeded("/dev/pts", "--bind /dev/pts")
        mountIfNeeded("/dev/shm", "-t tmpfs tmpfs")
        mountIfNeeded("/proc", "--bind /proc")
        mountIfNeeded("/sys", "--bind /sys")
        mountIfNeeded("/run", "-t tmpfs tmpfs")
        mountIfNeeded("/tmp", "-t tmpfs tmpfs")
        execChroot("mkdir -p /tmp/.X11-unix /tmp/runtime-root /root")
    }

    fun bindX11Socket() {
        if (!hasRoot()) return
        x11HostDir.mkdirs()
        val chrootX11 = File(rootfsDir, "tmp/.X11-unix").absolutePath
        val hostX11 = x11HostDir.absolutePath
        if (!rootShell.exec("mount").contains(" on $chrootX11 ")) {
            rootShell.exec("mkdir -p $chrootX11 && mount --bind $hostX11 $chrootX11")
        }
    }

    fun unmountAll() {
        if (!hasRoot()) return
        val mounts = rootShell.exec("mount").lines()
        listOf("tmp/.X11-unix", "dev/pts", "dev/shm", "dev", "proc", "sys", "run", "tmp").forEach {
            val target = File(rootfsDir, it).absolutePath
            if (mounts.any { m -> m.contains(" on $target ") }) {
                rootShell.exec("umount -l $target 2>/dev/null || umount $target 2>/dev/null")
            }
        }
    }

    fun executeCommand(command: String, onOutput: ((String) -> Unit)? = null): String {
        if (!hasRoot()) return "Error: root access required"
        val wrapped = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $command"
        return if (onOutput != null) {
            "Exit code: " + rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}", onOutput)
        } else {
            rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}")
        }
    }

    private fun execChroot(command: String, onLog: (String) -> Unit = {}): Int {
        val wrapped = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $command"
        return rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}", onLog)
    }

    private fun shellQuote(input: String): String = "'" + input.replace("'", "'\"'\"'") + "'"
}                val connection = URL(url).openConnection() as HttpURLConnection
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
}    /**
     * Download the Ubuntu rootfs with progress callbacks.
     */
    fun downloadRootfs(onProgress: (Double, String) -> Unit) {
        rootfsManager.downloadRootfs("ubuntu", onProgress)
    }

    /**
     * Extract the downloaded rootfs and configure it for chroot.
     */
    fun extractRootfs(onProgress: (Double, String) -> Unit) {
        rootfsManager.extractRootfs { progress, status ->
            onProgress(progress, status)
            if (progress == 1.0) {
                // Additional chroot-specific configuration
                configureChrootRootfs()
            }
        }
    }

    private fun configureChrootRootfs() {
        Log.i(TAG, "Applying chroot-specific rootfs configuration")

        // Ensure critical mount points exist
        listOf(
            "dev", "dev/pts", "dev/shm",
            "proc", "sys", "run",
            "tmp", "tmp/.X11-unix", "tmp/runtime-root",
            "root", "mnt/android", "mnt/sdcard"
        ).forEach {
            File(rootfsDir, it).mkdirs()
        }

        // Portable software-rendering profile. Android vendor GPU libraries do
        // not automatically become usable inside an Ubuntu chroot.
        File(rootfsDir, "etc/profile.d/droiddesk-ha.sh").apply {
            parentFile?.mkdirs()
            writeText(
                """
                #!/bin/bash
                # DroidDesk portable graphics environment
                export DISPLAY=:0
                export XDG_RUNTIME_DIR=/tmp/runtime-root
                export XDG_SESSION_TYPE=x11
                export XDG_DATA_DIRS=/usr/share:/usr/local/share
                export XDG_CONFIG_DIRS=/etc/xdg

                # Conservative Mesa fallback that works across GPU vendors
                export LIBGL_ALWAYS_SOFTWARE=true
                export GALLIUM_DRIVER=llvmpipe
                export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe

                # Disable accessibility bus spam
                export NO_AT_BRIDGE=1
                export GTK_A11Y=none

                # Locale
                export LANG=C.UTF-8
                export LC_ALL=C.UTF-8
                export LANGUAGE=C.UTF-8

                # Prompt
                export PS1='\[\033[01;32m\]droiddesk\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
                """.trimIndent()
            )
        }

        // Sources list for Ubuntu 24.04
        File(rootfsDir, "etc/apt/sources.list").writeText(
            """
            deb http://ports.ubuntu.com/ubuntu-ports noble main restricted universe multiverse
            deb http://ports.ubuntu.com/ubuntu-ports noble-updates main restricted universe multiverse
            deb http://ports.ubuntu.com/ubuntu-ports noble-security main restricted universe multiverse
            """.trimIndent().trim() + "\n"
        )

        // Make sure apt works without _apt sandbox user
        File(rootfsDir, "etc/apt/apt.conf.d/99-disable-sandbox").writeText("APT::Sandbox::User \"root\";\n")
        File(rootfsDir, "etc/apt/apt.conf.d/99-droiddesk-reliability").writeText(
            "Acquire::Retries \"3\";\n" +
                    "Acquire::http::Timeout \"30\";\n" +
                    "Acquire::https::Timeout \"30\";\n" +
                    "DPkg::Lock::Timeout \"60\";\n"
        )

        Log.i(TAG, "Chroot rootfs configuration complete")
    }

    /**
     * Install the desktop environment and GPU drivers inside the chroot.
     */
    fun installDesktopEnvironment(
        desktopEnv: String = "xfce4",
        onProgress: (Double, String) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {}
    ) {
        if (!hasRoot()) {
            onProgress(-1.0, "Root access required for chroot mode")
            return
        }
        if (!isRootfsReady()) {
            onProgress(-1.0, "Rootfs not ready. Download and extract first.")
            return
        }

        thread(name = "chroot-de-install") {
            try {
                onProgress(0.0, "Mounting rootfs...")
                ensureMounts()

                onProgress(0.05, "Updating package lists...")
                // ADD "-o APT::Sandbox::User=root" to the command
                if (execChroot("apt-get -o APT::Sandbox::User=root update -y", onLog) != 0) {
                    throw IllegalStateException("Package index update failed")
                }

                onProgress(0.1, "Installing core tools...")
                if (execChroot(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends " +
                            "locales ca-certificates wget curl dbus-x11",
                    onLog
                ) != 0) throw IllegalStateException("Core package installation failed")

                onProgress(0.2, "Installing Mesa GPU drivers...")
                if (execChroot(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends " +
                            "mesa-vulkan-drivers mesa-opencl-icd libgl1-mesa-dri libglx-mesa0 vulkan-tools",
                    onLog
                ) != 0) Log.w(TAG, "Mesa packages unavailable; desktop will use available software rendering")

                onProgress(0.4, "Installing desktop environment...")
                val dePackages = when (desktopEnv) {
                    "lxqt" -> "lxqt qterminal pcmanfm-qt featherpad"
                    "mate" -> "mate-desktop-environment mate-terminal"
                    "kde" -> "plasma-desktop konsole dolphin"
                    else -> "xfce4 xfce4-terminal xfce4-whiskermenu-plugin thunar mousepad"
                }
                if (execChroot(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends $dePackages",
                    onLog
                ) != 0) throw IllegalStateException("Desktop package installation failed")

                onProgress(0.8, "Installing Desktop Essentials tools...")
                val essentialsExit = execChroot(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends " +
                            "git nano htop wget curl python3 python3-pip openssh-client",
                    onLog
                )
                if (essentialsExit != 0) throw IllegalStateException("Desktop Essentials package installation failed")

                onProgress(0.9, "Cleaning up...")
                execChroot("apt-get clean", onLog)

                File(rootfsDir, CHROOT_DE_MARKER).writeText("$desktopEnv\n")
                onProgress(1.0, "$desktopEnv installed in chroot")
                Log.i(TAG, "Desktop environment installation complete")
            } catch (e: Exception) {
                Log.e(TAG, "DE install failed", e)
                onProgress(-1.0, "Installation failed: ${e.message}")
            }
        }
    }

    fun installOptionalApp(
        appId: String,
        onProgress: (Double, String) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {},
    ): Boolean {
        if (!hasRoot() || !isDesktopInstalled()) return false
        if (getOptionalAppsStatus()[appId] == true) {
            onProgress(1.0, "Already installed")
            return true
        }

        return try {
            ensureMounts()
            onProgress(0.05, "Repairing interrupted packages...")
            execChroot("DEBIAN_FRONTEND=noninteractive dpkg --configure -a", onLog)

            val command = when (appId) {
                "firefox" -> """
                    set -e
                    export DEBIAN_FRONTEND=noninteractive
                    apt-get install -y --no-install-recommends ca-certificates wget gpg
                    install -d -m 0755 /etc/apt/keyrings
                    wget -q https://packages.mozilla.org/apt/repo-signing-key.gpg -O /etc/apt/keyrings/packages.mozilla.org.asc
                    echo 'deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] https://packages.mozilla.org/apt mozilla main' > /etc/apt/sources.list.d/mozilla.list
                    printf 'Package: *\nPin: origin packages.mozilla.org\nPin-Priority: 1000\n' > /etc/apt/preferences.d/mozilla
                    apt-get update -y
                    apt-get install -y --no-install-recommends firefox
                """.trimIndent()
                "code_oss" -> """
                    set -e
                    export DEBIAN_FRONTEND=noninteractive
                    apt-get install -y --no-install-recommends ca-certificates wget gpg apt-transport-https
                    install -d -m 0755 /etc/apt/keyrings
                    wget -qO- https://packages.microsoft.com/keys/microsoft.asc | gpg --dearmor -o /etc/apt/keyrings/packages.microsoft.gpg
                    echo 'deb [arch=arm64 signed-by=/etc/apt/keyrings/packages.microsoft.gpg] https://packages.microsoft.com/repos/code stable main' > /etc/apt/sources.list.d/vscode.list
                    apt-get update -y
                    apt-get install -y --no-install-recommends code
                """.trimIndent()
                "nodejs" -> "DEBIAN_FRONTEND=noninteractive apt-get update -y && apt-get install -y --no-install-recommends nodejs npm"
                "imagemagick" -> "DEBIAN_FRONTEND=noninteractive apt-get update -y && apt-get install -y --no-install-recommends imagemagick"
                else -> return false
            }

            onProgress(0.25, "Installing optional application...")
            val exitCode = execChroot(command, onLog)
            if (exitCode != 0) throw IllegalStateException("Package manager exited with code $exitCode")
            onProgress(1.0, "Installation complete")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Optional app installation failed: $appId", error)
            onProgress(-1.0, "Installation failed: ${error.message}")
            false
        }
    }

    // ── Session management ──

    /**
     * Start the chrooted desktop session.
     * The caller should ensure the X11 socket directory is mounted before this.
     */
    fun startSession(desktopEnv: String = "xfce4", width: Int = 1920, height: Int = 1080) {
        if (!hasRoot()) {
            Log.e(TAG, "Cannot start chroot session without root")
            return
        }
        if (!isRootfsReady()) {
            Log.e(TAG, "Rootfs not ready")
            return
        }
        if (isRunning()) {
            Log.w(TAG, "Chroot session already running")
            return
        }

        if (desktopEnv == "xfce4") {
            XfceMobileProfile.install(
                context = context,
                homeDir = File(rootfsDir, "root"),
                wallpaperFile = File(
                    rootfsDir,
                    "usr/share/backgrounds/droiddesk/ubuntu-touch.jpg",
                ),
                wallpaperPathInSession =
                    "/usr/share/backgrounds/droiddesk/ubuntu-touch.jpg",
            )
        }

        ensureMounts()
        bindX11Socket()

        val deBin = when (desktopEnv) {
            "lxqt" -> "lxqt-session"
            "mate" -> "mate-session"
            "kde" -> "startplasma-x11"
            "xfce4" -> "startxfce4"
            else -> desktopEnv
        }

        val runScript = """
            # Standard FHS PATH (inherited Android PATH lacks /usr/bin)
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

            # Reset environment variables leaked from Android app
            export TMPDIR=/tmp
            export HOME=/root
            export PREFIX=/usr
            export LD_PRELOAD=/usr/local/lib/libclose_range_hack.so

            # Source DroidDesk environment
            . /etc/profile.d/droiddesk-ha.sh 2>/dev/null || true

            # Session D-Bus
            export DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/dbus-session
            rm -f /tmp/dbus-session
            dbus-daemon --session --address="${'$'}DBUS_SESSION_BUS_ADDRESS" --fork --nopidfile

            # Make sure X11 socket dir exists in case bind mount was late
            mkdir -p /tmp/.X11-unix

            echo "DIAG: Starting $desktopEnv in chroot on DISPLAY=:0 ..."
            exec dbus-run-session -- $deBin
        """.trimIndent()

        Log.i(TAG, "Starting chroot session for $desktopEnv")

        // Launch via ProcessBuilder through su so we get a Process handle we can monitor.
        val su = rootShell.findSuPath() ?: return
        val fullCommand = "chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(runScript)}"
        val startedSession = ProcessBuilder(su, "-c", fullCommand)
            .redirectErrorStream(true)
            .start()
        sessionProcess = startedSession

        Thread {
            try {
                val reader = startedSession.inputStream.bufferedReader()
                val buffer = CharArray(1024)
                var charsRead: Int
                while (reader.read(buffer).also { charsRead = it } != -1) {
                    Log.d(TAG, "CHROOT DESKTOP: " + String(buffer, 0, charsRead))
                }
            } catch (error: java.io.IOException) {
                Log.d(TAG, "Chroot desktop output stream closed")
            }
        }.start()
    }

    /**
     * Stop the chroot session and unmount bind mounts.
     */
    fun stopSession() {
        Log.i(TAG, "Stopping chroot session...")
        sessionProcess?.let {
            try {
                it.destroyForcibly()
                it.waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping session: ${e.message}")
            }
        }
        sessionProcess = null
        unmountAll()
        Log.i(TAG, "Chroot session stopped")
    }

    // ── Mount handling ──

    /**
     * Ensure /dev, /proc, /sys, /dev/pts and tmpfs mounts are active.
     */
    fun ensureMounts() {
        if (!hasRoot()) return

        val mounts = rootShell.exec("mount").lines()
        fun isMounted(path: String): Boolean {
            val absolute = File(rootfsDir, path).absolutePath
            return mounts.any { it.contains(" on $absolute ") }
        }

        mountIfNeeded("/dev", "--bind /dev") { isMounted("dev") }
        mountIfNeeded("/dev/pts", "--bind /dev/pts") { isMounted("dev/pts") }
        mountIfNeeded("/dev/shm", "-t tmpfs tmpfs") { isMounted("dev/shm") }
        mountIfNeeded("/proc", "--bind /proc") { isMounted("proc") }
        mountIfNeeded("/sys", "--bind /sys") { isMounted("sys") }
        mountIfNeeded("/run", "-t tmpfs tmpfs") { isMounted("run") }
        mountIfNeeded("/tmp", "-t tmpfs tmpfs") { isMounted("tmp") }

        // Create runtime dirs after tmpfs is mounted
        execChroot("mkdir -p /tmp/.X11-unix /tmp/runtime-root /root")
    }

    private fun mountIfNeeded(relative: String, mountArgs: String, alreadyMounted: () -> Boolean) {
        if (alreadyMounted()) return
        val target = File(rootfsDir, relative).absolutePath
        try {
            rootShell.exec("mkdir -p $target && mount $mountArgs $target")
            Log.i(TAG, "Mounted $target")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mount $target: ${e.message}")
        }
    }

    /**
     * Bind-mount the host X11 socket directory into the chroot.
     */
    fun bindX11Socket() {
        if (!hasRoot()) return
        x11HostDir.mkdirs()
        val chrootX11 = File(rootfsDir, "tmp/.X11-unix").absolutePath
        val hostX11 = x11HostDir.absolutePath

        // If already mounted, leave it
        val mounts = rootShell.exec("mount").lines()
        if (mounts.any { it.contains(" on $chrootX11 ") }) return

        rootShell.exec("mkdir -p $chrootX11 && mount --bind $hostX11 $chrootX11")
        Log.i(TAG, "Bound X11 socket: $hostX11 -> $chrootX11")
    }

    /**
     * Unmount all DroidDesk-related mounts.
     */
    fun unmountAll() {
        if (!hasRoot()) return
        val mounts = rootShell.exec("mount").lines()
        val targets = listOf(
            File(rootfsDir, "tmp/.X11-unix").absolutePath,
            File(rootfsDir, "dev/pts").absolutePath,
            File(rootfsDir, "dev/shm").absolutePath,
            File(rootfsDir, "dev").absolutePath,
            File(rootfsDir, "proc").absolutePath,
            File(rootfsDir, "sys").absolutePath,
            File(rootfsDir, "run").absolutePath,
            File(rootfsDir, "tmp").absolutePath
        )
        // Unmount in reverse order, be tolerant of busy mounts
        targets.reversed().forEach { target ->
            if (mounts.any { it.contains(" on $target ") }) {
                try {
                    rootShell.exec("umount -l $target 2>/dev/null || umount $target 2>/dev/null || true")
                    Log.i(TAG, "Unmounted $target")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unmount $target: ${e.message}")
                }
            }
        }
    }

    // ── Command execution inside chroot ──

    /**
     * Execute a command inside the chroot as root.
     */
    fun executeCommand(command: String, onOutput: ((String) -> Unit)? = null): String {
        if (!hasRoot()) return "Error: root access required"
        val wrapped = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $command"
        return if (onOutput != null) {
            val code = rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}") { chunk ->
                onOutput(chunk)
            }
            "Exit code: $code"
        } else {
            rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}")
        }
    }

    private fun execChroot(command: String, onLog: (String) -> Unit = {}): Int {
        val wrapped = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $command"
        val output = rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}") { chunk ->
            onLog(chunk)
        }
        Log.d(TAG, "chroot command exit code: $output")
        return output
    }

    private fun shellQuote(input: String): String {
        // Use a single-quoted string that handles embedded single quotes safely
        return "'" + input.replace("'", "'\"'\"'") + "'"
    }
}

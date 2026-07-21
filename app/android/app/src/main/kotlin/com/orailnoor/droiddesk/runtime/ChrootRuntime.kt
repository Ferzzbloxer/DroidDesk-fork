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
    
    fun isDesktopInstalled(): Boolean {
        val marker = File(context.filesDir, CHROOT_DE_MARKER)
        return marker.exists() || rootShell.exec("if [ -f \"${rootfsDir.absolutePath}/usr/bin/startxfce4\" ]; then echo 1; else echo 0; fi").trim() == "1"
    }
    
    fun isRunning(): Boolean = sessionProcess?.isAlive == true
    fun getRootfsPath(): String = rootfsDir.absolutePath
    fun getRootfsSizeMB(): Long = rootfsManager.getRootfsSizeMB()

    fun getOptionalAppsStatus(): Map<String, Boolean> {
        val firefox = rootShell.exec("if [ -f \"${rootfsDir.absolutePath}/usr/bin/firefox\" ]; then echo 1; fi").trim() == "1"
        val code = rootShell.exec("if [ -f \"${rootfsDir.absolutePath}/usr/bin/code\" ]; then echo 1; fi").trim() == "1"
        val node = rootShell.exec("if [ -f \"${rootfsDir.absolutePath}/usr/bin/node\" ]; then echo 1; fi").trim() == "1"
        val magick = rootShell.exec("if [ -f \"${rootfsDir.absolutePath}/usr/bin/convert\" ] || [ -f \"${rootfsDir.absolutePath}/usr/bin/magick\" ]; then echo 1; fi").trim() == "1"
        
        return mapOf(
            "firefox" to firefox,
            "code_oss" to code,
            "nodejs" to node,
            "imagemagick" to magick
        )
    }

    fun downloadRootfs(onProgress: (Double, String) -> Unit) {
        rootfsManager.downloadRootfs("ubuntu", onProgress)
    }

    fun extractRootfs(onProgress: (Double, String) -> Unit) {
        rootfsManager.extractRootfs { progress, status ->
            if (progress == 1.0) {
                configureChrootRootfs()
            }
            onProgress(progress, status)
        }
    }

    private fun writeRootFile(file: File, content: String) {
        rootShell.exec("mkdir -p \"${file.parentFile?.absolutePath}\"")
        rootShell.exec("cat << 'EOF' > \"${file.absolutePath}\"\n$content\nEOF")
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
        writeRootFile(File(rootfsDir, "etc/profile.d/droiddesk-ha.sh"), haScript)

        val sources = """
            deb http://ports.ubuntu.com/ubuntu-ports noble main restricted universe multiverse
            deb http://ports.ubuntu.com/ubuntu-ports noble-updates main restricted universe multiverse
            deb http://ports.ubuntu.com/ubuntu-ports noble-security main restricted universe multiverse
        """.trimIndent()
        writeRootFile(File(rootfsDir, "etc/apt/sources.list"), sources)

        val aptConf = """
            Acquire::Retries "3";
            Acquire::http::Timeout "30";
            Acquire::https::Timeout "30";
            DPkg::Lock::Timeout "60";
        """.trimIndent()
        writeRootFile(File(rootfsDir, "etc/apt/apt.conf.d/99-droiddesk-reliability"), aptConf)

        // --- VNC USB Auto-Listener Script ---
        val vncListenerScript = """
            #!/bin/bash
            USB_STATE_FILE="/sys/class/android_usb/android0/state"
            VNC_RUNNING=0
            
            while true; do
                if grep -q "CONFIGURED" "${'$'}USB_STATE_FILE" 2>/dev/null; then
                    if [ ${'$'}VNC_RUNNING -eq 0 ] && command -v x11vnc >/dev/null 2>&1; then
                        x11vnc -display :0 -rfbport 5901 -nopw -noshm -forever -bg >/dev/null 2>&1
                        VNC_RUNNING=1
                    fi
                else
                    if [ ${'$'}VNC_RUNNING -eq 1 ]; then
                        killall -9 x11vnc 2>/dev/null
                        VNC_RUNNING=0
                    fi
                fi
                sleep 3
            done
        """.trimIndent()
        writeRootFile(File(rootfsDir, "usr/local/bin/usb-vnc-listener.sh"), vncListenerScript)
        rootShell.exec("chmod +x \"$path/usr/local/bin/usb-vnc-listener.sh\"")
        // ------------------------------------------

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
                onProgress(0.0, "Configuring Ubuntu for Android...")
                configureChrootRootfs()

                onProgress(0.05, "Mounting virtual filesystems...")
                ensureMounts()

                onLog("Adding Android network groups to Linux...\n")
                execChroot("groupadd -g 3003 inet || true", onLog)
                execChroot("usermod -aG inet root || true", onLog)
                execChroot("usermod -aG inet _apt || true", onLog)

                onProgress(0.1, "Updating package lists...")
                if (execChroot("apt-get -o APT::Sandbox::User=root update -y", onLog) != 0) {
                    throw IllegalStateException("apt-get update failed. Check network connection.")
                }

                onProgress(0.2, "Installing core tools...")
                if (execChroot(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get -o APT::Sandbox::User=root install -y --no-install-recommends " +
                            "locales ca-certificates wget curl dbus-x11",
                    onLog
                ) != 0) throw IllegalStateException("Core package installation failed")

                onProgress(0.4, "Installing Mesa GPU drivers...")
                execChroot(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get -o APT::Sandbox::User=root install -y --no-install-recommends " +
                            "mesa-vulkan-drivers mesa-opencl-icd libgl1-mesa-dri libglx-mesa0 vulkan-tools",
                    onLog
                )

                onProgress(0.6, "Installing $desktopEnv...")
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

                onProgress(0.8, "Installing Essentials...")
                if (execChroot(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get -o APT::Sandbox::User=root install -y --no-install-recommends " +
                            "libgl1 git nano htop wget curl python3 python3-pip openssh-client x11vnc",
                    onLog
                ) != 0) throw IllegalStateException("Essentials installation failed")

                onProgress(0.9, "Cleaning up...")
                execChroot("apt-get clean", onLog)

                File(baseDir, CHROOT_DE_MARKER).writeText(desktopEnv)

                onProgress(1.0, "$desktopEnv installed in chroot")
                Log.i(TAG, "Desktop environment installation complete")
            } catch (e: Exception) {
                Log.e(TAG, "DE install failed", e)
                onProgress(-1.0, "Installation failed: ${e.message}")
            }
        }
    }

    fun installOptionalApp(appId: String, onProgress: (Double, String) -> Unit = { _, _ -> }, onLog: (String) -> Unit = {}): Boolean {
        if (!hasRoot() || !isRootfsReady()) return false
        
        return try {
            ensureMounts()
            execChroot("dpkg --configure -a", onLog)
            
            val command = when (appId) {
                "firefox" -> """
                    set -e
                    export DEBIAN_FRONTEND=noninteractive
                    apt-get -o APT::Sandbox::User=root install -y --no-install-recommends ca-certificates wget gpg
                    install -d -m 0755 /etc/apt/keyrings
                    wget -q https://packages.mozilla.org/apt/repo-signing-key.gpg -O /etc/apt/keyrings/packages.mozilla.org.asc
                    echo 'deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] https://packages.mozilla.org/apt mozilla main' > /etc/apt/sources.list.d/mozilla.list
                    printf 'Package: *\nPin: origin packages.mozilla.org\nPin-Priority: 1000\n' > /etc/apt/preferences.d/mozilla
                    apt-get -o APT::Sandbox::User=root update -y
                    apt-get -o APT::Sandbox::User=root install -y --no-install-recommends firefox
                """.trimIndent()
                "code_oss" -> """
                    set -e
                    export DEBIAN_FRONTEND=noninteractive
                    apt-get -o APT::Sandbox::User=root install -y --no-install-recommends ca-certificates wget gpg apt-transport-https
                    install -d -m 0755 /etc/apt/keyrings
                    wget -qO- https://packages.microsoft.com/keys/microsoft.asc | gpg --dearmor -o /etc/apt/keyrings/packages.microsoft.gpg
                    echo 'deb [arch=arm64 signed-by=/etc/apt/keyrings/packages.microsoft.gpg] https://packages.microsoft.com/repos/code stable main' > /etc/apt/sources.list.d/vscode.list
                    apt-get -o APT::Sandbox::User=root update -y
                    apt-get -o APT::Sandbox::User=root install -y --no-install-recommends code
                """.trimIndent()
                "nodejs" -> "DEBIAN_FRONTEND=noninteractive apt-get -o APT::Sandbox::User=root update -y && apt-get -o APT::Sandbox::User=root install -y --no-install-recommends nodejs npm"
                "imagemagick" -> "DEBIAN_FRONTEND=noninteractive apt-get -o APT::Sandbox::User=root update -y && apt-get -o APT::Sandbox::User=root install -y --no-install-recommends imagemagick"
                else -> return false
            }
            
            execChroot(command, onLog) == 0
        } catch (e: Exception) {
            Log.e(TAG, "Optional app install failed: $appId", e)
            false
        }
    }

    fun startSession(desktopEnv: String = "xfce4", width: Int = 1920, height: Int = 1080) {
        if (!hasRoot() || !isRootfsReady() || isRunning()) return

        val appUid = context.applicationInfo.uid
        val rootStr = "${rootfsDir.absolutePath}/root"

        // Wipe stale locks
        rootShell.exec("rm -rf \"$rootStr/.cache/sessions\" /tmp/.X11-unix/X0 /tmp/.X0-lock /tmp/dbus-* 2>/dev/null")
        
        // Network DNS fix
        rootShell.exec("rm -f \"${rootfsDir.absolutePath}/etc/resolv.conf\"")
        rootShell.exec("echo 'nameserver 8.8.8.8\nnameserver 1.1.1.1' > \"${rootfsDir.absolutePath}/etc/resolv.conf\"")
        
        // Temporarily give app write permissions for config creation
        rootShell.exec("mkdir -p \"$rootStr\" && chown -R $appUid:$appUid \"$rootStr\"")

        if (desktopEnv == "xfce4") {
            XfceMobileProfile.install(
                context = context,
                homeDir = File(rootfsDir, "root"),
                wallpaperFile = File(rootfsDir, "usr/share/backgrounds/droiddesk/ubuntu-touch.jpg"),
                wallpaperPathInSession = "/usr/share/backgrounds/droiddesk/ubuntu-touch.jpg"
            )
        }

        // Give ownership back to root so the Window Manager works securely
        rootShell.exec("chown -R 0:0 \"$rootStr\" && chmod -R 750 \"$rootStr\"")

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
            export TEMP=/tmp
            export TMP=/tmp
            export HOME=/root
            export USER=root
            export LOGNAME=root
            . /etc/profile.d/droiddesk-ha.sh 2>/dev/null || true
            export DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/dbus-session
            rm -f /tmp/dbus-session
            dbus-daemon --session --address="${'$'}DBUS_SESSION_BUS_ADDRESS" --fork --nopidfile
            mkdir -p /tmp/.X11-unix
            
            # Start USB VNC Listener
            /usr/local/bin/usb-vnc-listener.sh &
            
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
        
        // Force-kill all ghosts including our VNC script and x11vnc
        rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c 'killall -9 xfce4-session xfwm4 xfdesktop xfce4-panel dbus-daemon lxqt-session startplasma-x11 kwin_x11 usb-vnc-listener.sh x11vnc 2>/dev/null'")
        
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
        val wrapped = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; export TMPDIR=/tmp; export HOME=/root; $command"
        return if (onOutput != null) {
            "Exit code: " + rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}", onOutput)
        } else {
            rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}")
        }
    }

    private fun execChroot(command: String, onLog: (String) -> Unit = {}): Int {
        val wrapped = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; export TMPDIR=/tmp; export TEMP=/tmp; export TMP=/tmp; export HOME=/root; $command"
        return rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}", onLog)
    }

    private fun shellQuote(input: String): String = "'" + input.replace("'", "'\"'\"'") + "'"
}

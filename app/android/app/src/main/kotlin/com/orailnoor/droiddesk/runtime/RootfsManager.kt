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
    fun getRootfsSizeMB(): Long = if (isRootfsReady()) 350L else 0L

    // FIX: Ask the Root Shell to verify files, bypassing Android Java File permission blocks
    fun isRootfsReady(): Boolean {
        val rootShell = RootShell(context)
        val result = rootShell.exec("if [ -f \"${rootfsDir.absolutePath}/bin/bash\" ] && [ -d \"${rootfsDir.absolutePath}/etc\" ]; then echo 1; else echo 0; fi").trim()
        return result == "1"
    }

    fun getMissingPackages(): List<String> {
        if (!isRootfsReady()) return listOf("rootfs")
        val de = getInstalledDE().ifEmpty { "xfce4" }
        val rootShell = RootShell(context)
        val binExists = rootShell.exec("if [ -f \"${rootfsDir.absolutePath}/usr/bin/startxfce4\" ]; then echo 1; else echo 0; fi").trim() == "1"
        return if (binExists) emptyList() else listOf(de)
    }

    // Helper to write files inside root-owned folders safely
    private fun writeRootFile(file: File, content: String) {
        val rootShell = RootShell(context)
        rootShell.exec("mkdir -p \"${file.parentFile?.absolutePath}\"")
        rootShell.exec("cat << 'EOF' > \"${file.absolutePath}\"\n$content\nEOF")
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
                
                // Stream-extraction bypassing Android Toybox limitations on absolute symlinks
                val extractCmd = """
                    cd "${rootfsDir.absolutePath}" && 
                    if echo "${tarball.absolutePath}" | grep -q ".xz"; then 
                        xz -dc "${tarball.absolutePath}" | tar -x -p -P --no-same-owner 2>/dev/null; 
                    else 
                        zcat "${tarball.absolutePath}" | tar -x -p -P --no-same-owner 2>/dev/null; 
                    fi
                """.trimIndent().replace("\n", " ")
                
                rootShell.exec(extractCmd)

                // Verify success via Root
                val checkCmd = "if [ -f \"${rootfsDir.absolutePath}/bin/bash\" ]; then echo SUCCESS; else echo FAIL; fi"
                val result = rootShell.exec(checkCmd)
                if (!result.contains("SUCCESS")) {
                    throw RuntimeException("Extraction failed: Core OS files missing.")
                }

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
        val path = rootfsDir.absolutePath

        // We write the config files as root to avoid Kotlin Permission Denied issues
        writeRootFile(File(rootfsDir, "etc/apt/apt.conf.d/99-disable-sandbox"), "APT::Sandbox::User \"root\";\n")
        writeRootFile(File(rootfsDir, "etc/resolv.conf"), "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        writeRootFile(File(rootfsDir, "etc/hostname"), "droiddesk\n")
        writeRootFile(File(rootfsDir, "etc/hosts"), "127.0.0.1 localhost\n127.0.0.1 droiddesk\n::1 localhost ip6-localhost ip6-loopback\n")
        
        val droiddeskProfile = """
            #!/bin/bash
            export DISPLAY=:0
            export PULSE_SERVER=127.0.0.1
            export MESA_NO_ERROR=1
            export MESA_GL_VERSION_OVERRIDE=4.6
            export MESA_GLES_VERSION_OVERRIDE=3.2
            export GALLIUM_DRIVER=zink
            export MESA_LOADER_DRIVER_OVERRIDE=zink
            export TU_DEBUG=noconform
            export ZINK_DESCRIPTORS=lazy
            export MESA_VK_WSI_PRESENT_MODE=immediate
            export XDG_RUNTIME_DIR=/tmp/runtime-root
            mkdir -p "${'$'}XDG_RUNTIME_DIR" 2>/dev/null
            export PS1='\[\033[01;32m\]droiddesk\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
            alias ll='ls -la --color=auto'
            alias update='apt update && apt upgrade -y'
        """.trimIndent()
        writeRootFile(File(rootfsDir, "etc/profile.d/droiddesk.sh"), droiddeskProfile)
        writeRootFile(File(rootfsDir, "etc/sudoers.d/droiddesk"), "Defaults !requiretty\nroot ALL=(ALL) NOPASSWD: ALL\n")

        val rootShell = RootShell(context)
        rootShell.exec("mkdir -p \"$path/tmp\" \"$path/run\" \"$path/proc\" \"$path/sys\" \"$path/dev/pts\" \"$path/dev/shm\"")
    }

    private fun calculateDirSize(dir: File): Long {
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}

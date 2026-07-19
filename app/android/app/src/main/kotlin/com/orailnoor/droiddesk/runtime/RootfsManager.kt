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

                onProgress(0.2, "Extracting Linux Filesystem...")
                
                // One clean line to decompress, pipe to tar, handle android symlink issues, and silence standard errors
                val extractCmd = """
                    cd "${rootfsDir.absolutePath}" && 
                    if echo "${tarball.absolutePath}" | grep -q ".xz"; then 
                        xz -dc "${tarball.absolutePath}" | tar -x -p -P --no-same-owner 2>/dev/null; 
                    else 
                        zcat "${tarball.absolutePath}" | tar -x -p -P --no-same-owner 2>/dev/null; 
                    fi
                """.trimIndent().replace("\n", " ")
                
                rootShell.exec(extractCmd)

                // Verify success via Root Check
                val checkCmd = "if [ -f \"${rootfsDir.absolutePath}/bin/bash\" ]; then echo SUCCESS; else echo FAIL; fi"
                val result = rootShell.exec(checkCmd)
                if (!result.contains("SUCCESS")) {
                    throw RuntimeException("Extraction failed: Core OS files missing.")
                }

                // Give the Kotlin/Flutter layer permission to see inside the folder
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

        // Write configuration files safely using the root shell to bypass permission issues
        rootShell.exec("mkdir -p \"$path/etc/apt/apt.conf.d\"")
        rootShell.exec("echo 'APT::Sandbox::User \"root\";' > \"$path/etc/apt/apt.conf.d/99-disable-sandbox\"")
        rootShell.exec("echo 'name

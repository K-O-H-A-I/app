package com.sitta.core.data

import android.content.Context
import android.os.Environment
import com.sitta.core.common.AppResult
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class SessionExporter(private val context: Context) {
    fun exportAllSessions(sessionsRoot: File): AppResult<File> {
        return runCatching {
            val downloadsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val baseDir = File(downloadsRoot, "SITTA")
            val timestamp = System.currentTimeMillis()
            val output = File(baseDir, "sessions_$timestamp")
            val sessionsOut = File(output, "sessions")
            val targetDir = when {
                ensureDirWritable(sessionsOut) -> sessionsOut
                else -> {
                    val fallbackRoot = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: context.getExternalFilesDir(null)
                        ?: throw IllegalStateException("External storage not available")
                    val fallbackOutput = File(fallbackRoot, "sitta_sessions_$timestamp/sessions")
                    if (!ensureDirWritable(fallbackOutput)) {
                        throw IllegalStateException("Cannot write to external storage")
                    }
                    fallbackOutput
                }
            }
            copyDir(sessionsRoot, targetDir)
            targetDir.parentFile ?: targetDir
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Error("Export failed", it) },
        )
    }

    private fun ensureDirWritable(dir: File): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return false
            val probe = File(dir, ".probe")
            FileOutputStream(probe).use { it.write(1) }
            probe.delete()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun copyDir(source: File, dest: File) {
        val files = source.listFiles() ?: return
        files.forEach { file ->
            val target = File(dest, file.name)
            if (file.isDirectory) {
                if (!target.exists()) target.mkdirs()
                copyDir(file, target)
            } else {
                FileInputStream(file).use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}

package com.sitta.core.data

import android.content.Context
import android.os.Environment
import com.sitta.core.common.AppResult
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SessionExporter(private val context: Context) {
    fun exportAllSessions(sessionsRoot: File): AppResult<File> {
        return runCatching {
            val exportDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.getExternalFilesDir(null)
                ?: throw IllegalStateException("External storage not available")
            if (!exportDir.exists()) exportDir.mkdirs()
            val output = File(exportDir, "sitta_sessions_${System.currentTimeMillis()}.zip")
            ZipOutputStream(FileOutputStream(output)).use { zip ->
                addDir(zip, sessionsRoot, "sessions")
            }
            output
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Error("Export failed", it) },
        )
    }

    private fun addDir(zip: ZipOutputStream, dir: File, baseName: String) {
        val files = dir.listFiles() ?: return
        files.forEach { file ->
            val entryName = "$baseName/${file.name}"
            if (file.isDirectory) {
                addDir(zip, file, entryName)
            } else {
                FileInputStream(file).use { input ->
                    zip.putNextEntry(ZipEntry(entryName))
                    input.copyTo(zip)
                    zip.closeEntry()
                }
            }
        }
    }
}

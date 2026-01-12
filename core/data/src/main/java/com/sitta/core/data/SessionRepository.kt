package com.sitta.core.data

import android.content.Context
import android.graphics.Bitmap
import com.google.gson.Gson
import com.sitta.core.common.AppResult
import com.sitta.core.common.ArtifactFilenames
import com.sitta.core.common.SessionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class SessionRepository(
    private val context: Context,
    private val gson: Gson = Gson(),
) {
    private fun sessionsRoot(): File = File(context.filesDir, "sessions")

    private fun tenantRoot(tenantId: String): File = File(sessionsRoot(), tenantId)

    fun sessionsRootDir(): File = sessionsRoot()

    fun createSession(tenantId: String): AppResult<SessionInfo> {
        return runCatching {
            val sessionId = UUID.randomUUID().toString()
            val sessionDir = File(tenantRoot(tenantId), sessionId)
            if (!sessionDir.exists()) {
                sessionDir.mkdirs()
            }
            SessionInfo(tenantId = tenantId, sessionId = sessionId, timestamp = System.currentTimeMillis())
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Error("Failed to create session", it) },
        )
    }

    fun sessionDir(session: SessionInfo): File = File(tenantRoot(session.tenantId), session.sessionId)

    suspend fun saveBitmap(session: SessionInfo, filename: String, bitmap: Bitmap): AppResult<File> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = sessionDir(session)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val outputFile = File(dir, filename)
                FileOutputStream(outputFile).use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                outputFile
            }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = { AppResult.Error("Failed to save bitmap", it) },
            )
        }
    }

    suspend fun <T> saveJson(session: SessionInfo, filename: String, value: T): AppResult<File> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = sessionDir(session)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val outputFile = File(dir, filename)
                outputFile.writeText(gson.toJson(value))
                outputFile
            }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = { AppResult.Error("Failed to save JSON", it) },
            )
        }
    }

    fun loadBitmap(tenantId: String, sessionId: String, filename: String): File? {
        val file = File(tenantRoot(tenantId), "$sessionId/$filename")
        return if (file.exists()) file else null
    }

    fun loadLastSession(tenantId: String): SessionInfo? {
        val dir = tenantRoot(tenantId)
        if (!dir.exists()) return null
        val latest = dir.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.lastModified() }
        return latest?.let {
            SessionInfo(tenantId = tenantId, sessionId = it.name, timestamp = it.lastModified())
        }
    }

    fun listSessions(tenantId: String): List<SessionInfo> {
        val dir = tenantRoot(tenantId)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter { it.isDirectory }?.map {
            SessionInfo(tenantId = tenantId, sessionId = it.name, timestamp = it.lastModified())
        }?.sortedByDescending { it.timestamp } ?: emptyList()
    }

    fun sessionArtifactPath(session: SessionInfo, filename: String): File {
        return File(sessionDir(session), filename)
    }

    fun lastRawPath(tenantId: String): File? {
        val session = loadLastSession(tenantId) ?: return null
        return loadBitmap(tenantId, session.sessionId, ArtifactFilenames.RAW)
    }
}

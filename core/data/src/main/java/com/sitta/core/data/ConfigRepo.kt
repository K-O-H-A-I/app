package com.sitta.core.data

import android.content.Context
import com.google.gson.Gson
import com.sitta.core.common.AppConfig
import com.sitta.core.common.DefaultConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

class ConfigRepo(
    private val context: Context,
    private val gson: Gson = Gson(),
) {
    private val configState = MutableStateFlow(DefaultConfig.value)

    val config: StateFlow<AppConfig> = configState

    suspend fun load() {
        val loaded = withContext(Dispatchers.IO) {
            readAsset("config.json") ?: readAsset("default_config.json")
        }
        if (loaded != null) {
            configState.value = loaded
        }
    }

    fun current(): AppConfig = configState.value

    private fun readAsset(filename: String): AppConfig? {
        return runCatching {
            context.assets.open(filename).use { stream ->
                InputStreamReader(stream).use { reader ->
                    gson.fromJson(reader, AppConfig::class.java)
                }
            }
        }.getOrNull()
    }
}

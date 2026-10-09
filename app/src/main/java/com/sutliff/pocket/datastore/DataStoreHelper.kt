package com.sutliff.pocket.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.sutliff.pocket.data.InferenceConfig

private val Context.dataStore by preferencesDataStore(name = "agent_config")

private val INFERENCE_CONFIG_KEY = stringPreferenceKey("inference_config")

suspend fun saveInferenceConfig(context: Context, config: InferenceConfig) {
    val json = Json { ignoreUnknownKeys = true }
    val jsonString = json.encodeToString(InferenceConfig.serializer(), config)
    context.dataStore.edit { prefs ->
        prefs[INFERENCE_CONFIG_KEY] = jsonString
    }
}

suspend fun getInferenceConfig(context: Context): Flow<InferenceConfig> {
    return context.dataStore.data.map { prefs ->
        val jsonString = prefs[INFERENCE_CONFIG_KEY] ?: ""
        if (jsonString.isEmpty()) {
            InferenceConfig()
        } else {
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString(InferenceConfig.serializer(), jsonString)
        }
    }
}

suspend fun readConfig(context: Context): InferenceConfig = runBlocking {
    getInferenceConfig(context).first()
}
package org.cryptgregresearch.dontree.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("dontree")
data class Preferences(val online: Boolean = false, val acoustIdKey: String = "", val favorites: Set<String> = emptySet(), val romanization: Boolean = true, val translation: Boolean = true,
    val lyricsOnline: Boolean = false, val lyricsEndpoint: String = "", val lyricsApiKey: String = "")

class Settings(context: Context) {
    private val store = context.settingsStore
    private val onlineKey = booleanPreferencesKey("online_metadata")
    private val apiKey = stringPreferencesKey("acoustid_key")
    private val favoritesKey = stringSetPreferencesKey("favorites")
    private val romanKey = booleanPreferencesKey("romanization")
    private val translationKey = booleanPreferencesKey("translation")
    private val queueKey = stringPreferencesKey("queue")
    private val lyricsOnlineKey = booleanPreferencesKey("lyrics_online")
    private val lyricsEndpointKey = stringPreferencesKey("lyrics_endpoint")
    private val lyricsApiKeyKey = stringPreferencesKey("lyrics_api_key")
    val values = store.data.map { Preferences(it[onlineKey] ?: false, it[apiKey] ?: "", it[favoritesKey] ?: emptySet(), it[romanKey] ?: true, it[translationKey] ?: true,
        it[lyricsOnlineKey] ?: false, it[lyricsEndpointKey] ?: "", it[lyricsApiKeyKey] ?: "") }
    suspend fun lyricsOnline(value: Boolean) { store.edit { it[lyricsOnlineKey] = value } }
    suspend fun lyricsService(endpoint: String, key: String) {
        store.edit { it[lyricsEndpointKey] = endpoint.trim(); it[lyricsApiKeyKey] = key.trim() }
    }
    val queue = store.data.map { it[queueKey] }
    suspend fun online(value: Boolean) { store.edit { it[onlineKey] = value } }
    suspend fun apiKey(value: String) { store.edit { it[apiKey] = value.trim() } }
    suspend fun romanization(value: Boolean) { store.edit { it[romanKey] = value } }
    suspend fun translation(value: Boolean) { store.edit { it[translationKey] = value } }
    suspend fun favorite(id: Long) { store.edit { prefs ->
        val old = prefs[favoritesKey] ?: emptySet()
        prefs[favoritesKey] = if (id.toString() in old) old - id.toString() else old + id.toString()
    } }
    suspend fun saveQueue(json: String) { store.edit { it[queueKey] = json } }
}

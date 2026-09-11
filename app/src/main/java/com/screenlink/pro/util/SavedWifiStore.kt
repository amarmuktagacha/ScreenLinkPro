package com.screenlink.pro.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedWifi(val name: String, val password: String)

class SavedWifiStore(context: Context) {
    private val prefs = context.getSharedPreferences("saved_wifi_profiles", Context.MODE_PRIVATE)
    private val key = "profiles"

    fun list(): List<SavedWifi> = try {
        val array = JSONArray(prefs.getString(key, "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                if (name.isNotBlank()) add(SavedWifi(name, item.optString("password")))
            }
        }
    } catch (_: Exception) { emptyList() }

    fun save(profile: SavedWifi) {
        val updated = list().filterNot { it.name == profile.name }.toMutableList()
        updated.add(0, profile)
        val array = JSONArray()
        updated.take(10).forEach { array.put(JSONObject().put("name", it.name).put("password", it.password)) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    fun remove(name: String) {
        val array = JSONArray()
        list().filterNot { it.name == name }.forEach { array.put(JSONObject().put("name", it.name).put("password", it.password)) }
        prefs.edit().putString(key, array.toString()).apply()
    }
}

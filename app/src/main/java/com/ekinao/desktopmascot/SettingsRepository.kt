package com.ekinao.desktopmascot

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "mascot_settings")

data class MascotSettings(
    val sitBeforeWalkMin: Int = 60,
    val sitBeforeWalkMax: Int = 90,
    val walkDurationMin: Int = 4,
    val walkDurationMax: Int = 10,
    val sitAfterWalkMin: Int = 10,
    val sitAfterWalkMax: Int = 20,
    val restMin: Int = 30,
    val restMax: Int = 90,
    val walkSpeed: Double = 1.0,
    val screenMargin: Int = 50,
    val disableBreathingAnimation: Boolean = false,
    val hideWhenLowBattery: Boolean = true,
    val batteryThreshold: Int = 20,
    val useCustomMascot: Boolean = false,
    val customSitImages: List<String> = emptyList(),
    val customWalkLeftImages: List<String> = emptyList(),
    val customWalkRightImages: List<String> = emptyList(),
    val customRestImages: List<String> = emptyList(),
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val sitBeforeWalkMin = intPreferencesKey("sit_before_walk_min")
        val sitBeforeWalkMax = intPreferencesKey("sit_before_walk_max")
        val walkDurationMin = intPreferencesKey("walk_duration_min")
        val walkDurationMax = intPreferencesKey("walk_duration_max")
        val sitAfterWalkMin = intPreferencesKey("sit_after_walk_min")
        val sitAfterWalkMax = intPreferencesKey("sit_after_walk_max")
        val restMin = intPreferencesKey("rest_min")
        val restMax = intPreferencesKey("rest_max")
        val walkSpeed = doublePreferencesKey("walk_speed")
        val screenMargin = intPreferencesKey("screen_margin")
        val disableBreathingAnimation = booleanPreferencesKey("disable_breathing_animation")
        val hideWhenLowBattery = booleanPreferencesKey("hide_when_low_battery")
        val batteryThreshold = intPreferencesKey("battery_threshold")
        val useCustomMascot = booleanPreferencesKey("use_custom_mascot")
        val customSitImages = stringPreferencesKey("custom_sit_images")
        val customWalkLeftImages = stringPreferencesKey("custom_walk_left_images")
        val customWalkRightImages = stringPreferencesKey("custom_walk_right_images")
        val customRestImages = stringPreferencesKey("custom_rest_images")
    }

    val settings: Flow<MascotSettings> = context.dataStore.data.map { p ->
        MascotSettings(
            sitBeforeWalkMin = p[Keys.sitBeforeWalkMin] ?: 60,
            sitBeforeWalkMax = p[Keys.sitBeforeWalkMax] ?: 90,
            walkDurationMin = p[Keys.walkDurationMin] ?: 4,
            walkDurationMax = p[Keys.walkDurationMax] ?: 10,
            sitAfterWalkMin = p[Keys.sitAfterWalkMin] ?: 10,
            sitAfterWalkMax = p[Keys.sitAfterWalkMax] ?: 20,
            restMin = p[Keys.restMin] ?: 30,
            restMax = p[Keys.restMax] ?: 90,
            walkSpeed = p[Keys.walkSpeed] ?: 1.0,
            screenMargin = p[Keys.screenMargin] ?: 50,
            disableBreathingAnimation = p[Keys.disableBreathingAnimation] ?: false,
            hideWhenLowBattery = p[Keys.hideWhenLowBattery] ?: true,
            batteryThreshold = p[Keys.batteryThreshold] ?: 20,
            useCustomMascot = p[Keys.useCustomMascot] ?: false,
            customSitImages = decodeList(p[Keys.customSitImages]),
            customWalkLeftImages = decodeList(p[Keys.customWalkLeftImages]),
            customWalkRightImages = decodeList(p[Keys.customWalkRightImages]),
            customRestImages = decodeList(p[Keys.customRestImages]),
        )
    }

    suspend fun update(block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    suspend fun save(s: MascotSettings) {
        context.dataStore.edit { p ->
            p[Keys.sitBeforeWalkMin] = s.sitBeforeWalkMin
            p[Keys.sitBeforeWalkMax] = s.sitBeforeWalkMax
            p[Keys.walkDurationMin] = s.walkDurationMin
            p[Keys.walkDurationMax] = s.walkDurationMax
            p[Keys.sitAfterWalkMin] = s.sitAfterWalkMin
            p[Keys.sitAfterWalkMax] = s.sitAfterWalkMax
            p[Keys.restMin] = s.restMin
            p[Keys.restMax] = s.restMax
            p[Keys.walkSpeed] = s.walkSpeed
            p[Keys.screenMargin] = s.screenMargin
            p[Keys.disableBreathingAnimation] = s.disableBreathingAnimation
            p[Keys.hideWhenLowBattery] = s.hideWhenLowBattery
            p[Keys.batteryThreshold] = s.batteryThreshold
            p[Keys.useCustomMascot] = s.useCustomMascot
            p[Keys.customSitImages] = encodeList(s.customSitImages)
            p[Keys.customWalkLeftImages] = encodeList(s.customWalkLeftImages)
            p[Keys.customWalkRightImages] = encodeList(s.customWalkRightImages)
            p[Keys.customRestImages] = encodeList(s.customRestImages)
        }
    }

    suspend fun resetMascotImages() {
        context.dataStore.edit { p ->
            p[Keys.useCustomMascot] = false
            p[Keys.customSitImages] = ""
            p[Keys.customWalkLeftImages] = ""
            p[Keys.customWalkRightImages] = ""
            p[Keys.customRestImages] = ""
        }
        val dir = context.filesDir.resolve(CUSTOM_MASCOT_DIR)
        dir.deleteRecursively()
    }

    companion object {
        const val CUSTOM_MASCOT_DIR = "custom_mascot"

        private fun encodeList(values: List<String>): String =
            values.joinToString(SEP) { UriCodec.encode(it) }

        private fun decodeList(value: String?): List<String> =
            value.orEmpty().split(SEP)
                .filter { it.isNotBlank() }
                .mapNotNull { UriCodec.decode(it) }

        private const val SEP = "|"
    }
}

private object UriCodec {
    fun encode(value: String): String = android.net.Uri.encode(value, "")
    fun decode(value: String): String? = try {
        android.net.Uri.decode(value)
    } catch (_: Exception) {
        null
    }
}

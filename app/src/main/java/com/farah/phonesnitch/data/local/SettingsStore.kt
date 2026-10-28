package com.farah.phonesnitch.data.local

import android.content.Context
import androidx.core.content.edit
import com.farah.phonesnitch.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsStore(context: Context) : SettingsRepository {

    private val prefs = context.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)

    private val _detoxEnabled = MutableStateFlow(prefs.getBoolean(KEY_DETOX_ENABLED, false))
    private val _detoxStart = MutableStateFlow(
        prefs.getLong(KEY_DETOX_START, NONE).takeIf { it != NONE }
    )

    override val detoxEnabled: StateFlow<Boolean> = _detoxEnabled
    override val detoxStartEpochDay: StateFlow<Long?> = _detoxStart

    override var lastClassifiedEpochDay: Long
        get() = prefs.getLong(KEY_LAST_CLASSIFIED, NONE)
        set(value) = prefs.edit { putLong(KEY_LAST_CLASSIFIED, value) }

    override var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_ONBOARDING_DONE, value) }

    override var lastUsageImportMillis: Long
        get() = prefs.getLong(KEY_LAST_USAGE_IMPORT, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_USAGE_IMPORT, value) }

    override fun setDetoxEnabled(enabled: Boolean, todayEpochDay: Long) {
        prefs.edit {
            putBoolean(KEY_DETOX_ENABLED, enabled)
            if (enabled) putLong(KEY_DETOX_START, todayEpochDay) else remove(KEY_DETOX_START)
        }
        _detoxStart.value = if (enabled) todayEpochDay else null
        _detoxEnabled.value = enabled
    }

    private companion object {
        const val NONE = -1L
        const val KEY_DETOX_ENABLED = "detox_enabled"
        const val KEY_DETOX_START = "detox_start_epoch_day"
        const val KEY_LAST_CLASSIFIED = "last_classified_epoch_day"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
        const val KEY_LAST_USAGE_IMPORT = "last_usage_import_millis"
    }
}

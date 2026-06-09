package au.net.kal.teslasync.data

import android.content.Context

/**
 * Typed access to all persisted settings. The Tessie token lives in [SecureStore]
 * (encrypted); everything else is plain app-private SharedPreferences.
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val secure = SecureStore(appContext)
    private val prefs = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var tessieToken: String?
        get() = secure.getString(KEY_TOKEN)
        set(value) = secure.putString(KEY_TOKEN, value)

    var vin: String?
        get() = prefs.getString(KEY_VIN, null)
        set(value) { prefs.edit().putString(KEY_VIN, value).apply() }

    /** Bluetooth MAC of the car, chosen via CompanionDeviceManager, for auto-arm. */
    var carBluetoothAddress: String?
        get() = prefs.getString(KEY_BT_ADDR, null)
        set(value) { prefs.edit().putString(KEY_BT_ADDR, value).apply() }

    var autoArmOnBluetooth: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ARM, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_ARM, value).apply() }

    /** If true, firing on a destination that is ALREADY set the moment we arm. */
    var fireOnArm: Boolean
        get() = prefs.getBoolean(KEY_FIRE_ON_ARM, true)
        set(value) { prefs.edit().putBoolean(KEY_FIRE_ON_ARM, value).apply() }

    var pollIntervalSeconds: Int
        get() = prefs.getInt(KEY_POLL, DEFAULT_POLL_SECONDS)
        set(value) { prefs.edit().putInt(KEY_POLL, value.coerceIn(MIN_POLL, MAX_POLL)).apply() }

    /** The update version the user dismissed, so we don't nag for the same one again. */
    var dismissedUpdateVersion: String?
        get() = prefs.getString(KEY_DISMISSED_UPDATE, null)
        set(value) { prefs.edit().putString(KEY_DISMISSED_UPDATE, value).apply() }

    val isConfigured: Boolean
        get() = !tessieToken.isNullOrBlank() && !vin.isNullOrBlank()

    companion object {
        const val MIN_POLL = 5
        const val MAX_POLL = 120
        const val DEFAULT_POLL_SECONDS = 15

        private const val FILE_NAME = "teslasync"
        private const val KEY_TOKEN = "tessie_token"
        private const val KEY_VIN = "vin"
        private const val KEY_BT_ADDR = "car_bt_address"
        private const val KEY_AUTO_ARM = "auto_arm_bt"
        private const val KEY_FIRE_ON_ARM = "fire_on_arm"
        private const val KEY_POLL = "poll_interval_s"
        private const val KEY_DISMISSED_UPDATE = "dismissed_update_version"
    }
}

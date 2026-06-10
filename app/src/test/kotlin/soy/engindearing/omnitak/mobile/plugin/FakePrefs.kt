package soy.engindearing.omnitak.mobile.plugin

import android.content.SharedPreferences

/**
 * Minimal [SharedPreferences] for JVM unit tests. Only [getBoolean] is
 * meaningful — that's all [soy.engindearing.omnitak.plugin.PluginRegistry]
 * reads. Everything else throws so a future caller can't silently rely on a
 * stub returning defaults.
 */
class FakePrefs(private val bools: Map<String, Boolean>) : SharedPreferences {
    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        bools[key] ?: defValue

    override fun getAll(): MutableMap<String, *> = bools.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = defValue
    override fun getLong(key: String, defValue: Long): Long = defValue
    override fun getFloat(key: String, defValue: Float): Float = defValue
    override fun contains(key: String): Boolean = bools.containsKey(key)
    override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

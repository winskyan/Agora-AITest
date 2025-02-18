package io.agora.ai.test.utils

import com.tencent.mmkv.MMKV

object MMKVUtils {
    private val mmkv: MMKV = MMKV.defaultMMKV()

    fun putString(key: String, value: String) {
        mmkv.encode(key, value)
    }

    fun getString(key: String, defaultValue: String = ""): String {
        return mmkv.decodeString(key, defaultValue) ?: defaultValue
    }

    fun putStringSet(key: String, value: Set<String>) {
        mmkv.encode(key, value)
    }

    fun getStringSet(key: String, defaultValue: Set<String> = emptySet()): Set<String>? {
        return mmkv.decodeStringSet(key, defaultValue)
    }

    fun putInt(key: String, value: Int) {
        mmkv.encode(key, value)
    }

    fun getInt(key: String, defaultValue: Int = 0): Int {
        return mmkv.decodeInt(key, defaultValue)
    }

    fun putBoolean(key: String, value: Boolean) {
        mmkv.encode(key, value)
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return mmkv.decodeBool(key, defaultValue)
    }

    fun remove(key: String) {
        mmkv.removeValueForKey(key)
    }
}
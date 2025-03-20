package io.agora.ai.rtm.test.utils

import android.content.Context
import android.util.Log
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.UUID


object Utils {

    fun generateUniqueRandom(context: Context): String {
        val seed = StringBuilder()

        val timestamp = System.currentTimeMillis()
        val uuid = UUID.randomUUID().toString()

        seed.append(timestamp)
            .append(uuid)


        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(seed.toString().toByteArray())
            
            val hexString = StringBuilder()
            for (b in hash) {
                val hex = Integer.toHexString(0xff and b.toInt())
                if (hex.length == 1) hexString.append('0')
                hexString.append(hex)
            }
            return hexString.toString()
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
            return UUID.randomUUID().toString()
        }
    }
}
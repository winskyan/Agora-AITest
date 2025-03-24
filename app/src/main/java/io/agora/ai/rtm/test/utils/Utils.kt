package io.agora.ai.rtm.test.utils

import android.content.Context
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

    fun byteArrayToBase64(bytes: ByteArray): String {
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    fun base64ToByteArray(base64String: String): ByteArray {
        return android.util.Base64.decode(base64String, android.util.Base64.NO_WRAP)
    }

}
package io.agora.ai.test.maas.model

import java.util.Arrays

class MassEncryptionConfig {
    var encryptionMode: EncryptionMode
    var encryptionKey: String? = null
    val encryptionKdfSalt: ByteArray = ByteArray(32)
    var datastreamEncryptionEnabled: Boolean = false

    init {
        this.encryptionMode = EncryptionMode.AES_128_GCM2
        Arrays.fill(this.encryptionKdfSalt, 0.toByte())
    }

    enum class EncryptionMode(val value: Int) {
        AES_128_XTS(1),
        AES_128_ECB(2),
        AES_256_XTS(3),
        SM4_128_ECB(4),
        AES_128_GCM(5),
        AES_256_GCM(6),
        AES_128_GCM2(7),
        AES_256_GCM2(8),
        MODE_END(9)
    }

    override fun toString(): String {
        return "MassEncryptionConfig{" +
                "encryptionMode=" + encryptionMode +
                ", encryptionKey='" + encryptionKey + '\'' +
                ", encryptionKdfSalt=" + encryptionKdfSalt.contentToString() +
                ", datastreamEncryptionEnabled=" + datastreamEncryptionEnabled +
                '}'
    }
}

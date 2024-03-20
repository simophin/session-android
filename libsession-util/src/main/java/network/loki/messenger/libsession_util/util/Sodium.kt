package network.loki.messenger.libsession_util.util

object Sodium {
    init {
        System.loadLibrary("session_util")
    }
    external fun ed25519KeyPair(seed: ByteArray): KeyPair
    external fun ed25519PkToCurve25519(pk: ByteArray): ByteArray
    external fun encryptForMultipleSimple(
        message: String,
        recipient: String,
        ed25519SecretKey: ByteArray,
        domain: String
    ): ByteArray?
    external fun decryptForMultipleSimple(
        encoded: ByteArray,
        ed25519SecretKey: ByteArray,
        senderPubKey: ByteArray,
        domain: String,
    ): ByteArray?
}
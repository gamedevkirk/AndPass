package com.sneakyshiba.andpass

import android.content.Context
import java.io.File

class PgpKeyStore(
    private val context: Context,
) {
    private val pgpDirectory: File
        get() = File(context.filesDir, "pgp")

    private val armoredPrivateKeyFile: File
        get() = File(pgpDirectory, "private-key.asc")

    private val binaryPrivateKeyFile: File
        get() = File(pgpDirectory, "private-key.gpg")

    fun hasPrivateKey(): Boolean = getPrivateKeyFileOrNull() != null

    fun getPrivateKeyFileOrNull(): File? =
        when {
            armoredPrivateKeyFile.exists() -> armoredPrivateKeyFile
            binaryPrivateKeyFile.exists() -> binaryPrivateKeyFile
            else -> null
        }

    fun savePrivateKey(bytes: ByteArray): File {
        pgpDirectory.mkdirs()

        armoredPrivateKeyFile.delete()
        binaryPrivateKeyFile.delete()

        val targetFile =
            if (isArmoredPrivateKey(bytes)) {
                armoredPrivateKeyFile
            } else {
                binaryPrivateKeyFile
            }

        targetFile.writeBytes(bytes)
        return targetFile
    }

    fun clearPrivateKey() {
        armoredPrivateKeyFile.delete()
        binaryPrivateKeyFile.delete()
    }

    private fun isArmoredPrivateKey(bytes: ByteArray): Boolean {
        val prefix =
            bytes
                .take(256)
                .toByteArray()
                .toString(Charsets.UTF_8)

        return prefix.contains("-----BEGIN PGP PRIVATE KEY BLOCK-----")
    }
}

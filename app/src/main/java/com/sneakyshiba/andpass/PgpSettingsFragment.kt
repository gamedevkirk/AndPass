package com.sneakyshiba.andpass

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.sneakyshiba.andpass.databinding.FragmentPgpSettingsBinding
import java.io.ByteArrayOutputStream

class PgpSettingsFragment : Fragment(R.layout.fragment_pgp_settings) {
    private var _binding: FragmentPgpSettingsBinding? = null
    private val binding: FragmentPgpSettingsBinding
        get() = _binding!!

    private lateinit var pgpKeyStore: PgpKeyStore

    private val privateKeyPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importPrivateKey(uri)
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        _binding = FragmentPgpSettingsBinding.bind(view)
        pgpKeyStore = PgpKeyStore(requireContext())

        binding.importPgpKeyButton.setOnClickListener {
            privateKeyPicker.launch(
                arrayOf(
                    "application/pgp-keys",
                    "application/octet-stream",
                    "text/plain",
                    "*/*"
                )
            )
        }

        binding.clearPgpKeyButton.setOnClickListener {
            pgpKeyStore.clearPrivateKey()
            refreshStatus()
        }

        refreshStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun importPrivateKey(uri: Uri) {
        setLoading(true)

        Thread {
            val result = runCatching {
                val bytes = readUriBytes(uri)
                val savedFile = pgpKeyStore.savePrivateKey(bytes)
                "Private key imported and ready to decrypt."
            }.getOrElse { exception ->
                "Unable to import private key.\n\n${exception.message ?: exception.javaClass.simpleName}"
            }

            activity?.runOnUiThread {
                setLoading(false)
                binding.pgpSettingsStatus.text = result
            }
        }.start()
    }

    private fun readUriBytes(uri: Uri): ByteArray {
        val inputStream = requireContext().contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Unable to open selected file.")

        inputStream.use { input ->
            val output = ByteArrayOutputStream()
            input.copyTo(output)
            return output.toByteArray()
        }
    }

    private fun refreshStatus() {
        val privateKeyFile = pgpKeyStore.getPrivateKeyFileOrNull()

        binding.pgpSettingsStatus.text =
            if (privateKeyFile == null) {
                "No private key imported."
            } else {
                "Private key imported and ready to decrypt."
            }
    }

    private fun setLoading(isLoading: Boolean) {
        binding.pgpSettingsStatus.visibility = if (isLoading) View.GONE else View.VISIBLE
        binding.pgpSettingsLoadingBar.visibility = if (isLoading) View.VISIBLE else View.GONE

        binding.importPgpKeyButton.isEnabled = !isLoading
        binding.clearPgpKeyButton.isEnabled = !isLoading
    }
}

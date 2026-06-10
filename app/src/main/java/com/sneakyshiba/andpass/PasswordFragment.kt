package com.sneakyshiba.andpass

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Button
import android.widget.TextView
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.ScrollView
import android.widget.Toast
import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.fragment.app.Fragment
import com.sneakyshiba.andpass.databinding.FragmentPasswordsBinding
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class PasswordsFragment : Fragment(R.layout.fragment_passwords) {

    private var _binding: FragmentPasswordsBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: GitSettingsStore

    private val mainActivity: MainActivity
        get() = requireActivity() as MainActivity

    private var currentRelativePath: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentPasswordsBinding.bind(view)
        store = GitSettingsStore(requireContext())

        setLoading(false)
        refreshInitialState()

        binding.syncPasswordsButton.setOnClickListener {
            runLatentAction {
                syncAndListPasswords()
            }
        }
    }

    private fun renderRawListResult(result: String) {
        binding.passwordsList.removeAllViews()

        if (result.startsWith("ERROR\n") || result.startsWith("MESSAGE\n")) {
            renderMessage(result.substringAfter('\n'))
            return
        }

        val lines = result.lines().filter { it.isNotBlank() }

        addPathHeader(
            if (currentRelativePath.isNotBlank()) {
                currentRelativePath
            } else {
                "/"
            }
        )

        var rowIndex = 0

        if (currentRelativePath.isNotBlank()) {
            addDirectoryNavigationRow("←  Back", rowIndex++) {
                currentRelativePath = currentRelativePath.substringBeforeLast("/", "")
                val storagePath = ensureStorageDirectory().getOrThrow()
                renderRepositoryEntries(storagePath, currentRelativePath)
            }
        }

        for (line in lines) {
            val parts = line.split("\t", limit = 2)
            if (parts.size != 2) {
                continue
            }

            val type = parts[0]
            val name = parts[1]

            when (type) {
                "DIR" -> {
                    addDirectoryNavigationRow("📁  $name", rowIndex++) {
                        currentRelativePath =
                            if (currentRelativePath.isBlank()) {
                                name
                            } else {
                                "$currentRelativePath/$name"
                            }

                        val storagePath = ensureStorageDirectory().getOrThrow()
                        renderRepositoryEntries(storagePath, currentRelativePath)
                    }
                }

                "FILE" -> {
                    val encryptedFilePath = resolveEncryptedPasswordFilePath(name)
                    addFileRow(
                        label = "🔐  $name",
                        rowIndex = rowIndex++,
                        encryptedFilePath = encryptedFilePath
                    )
                }
            }
        }
    }

    private fun addDirectoryNavigationRow(label: String, rowIndex: Int, action: () -> Unit) {
        val row = android.widget.TextView(requireContext()).apply {
            text = label
            textSize = 18f
            minHeight = dp(56)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            setTextColor(rowTextColor())
            setBackgroundColor(rowBackgroundColor(rowIndex))
            isClickable = true
            isFocusable = true

            setOnClickListener {
                action()
            }
        }

        binding.passwordsList.addView(row)
    }

    private fun addFileRow(label: String, rowIndex: Int, encryptedFilePath: String) {
        val row = android.widget.TextView(requireContext()).apply {
            text = label
            textSize = 18f
            minHeight = dp(56)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            setTextColor(rowTextColor())
            setBackgroundColor(rowBackgroundColor(rowIndex))
            isClickable = true
            isFocusable = true
    
            setOnClickListener {
                val privateKeyFile = try {
                    resolvePrivateKeyFile()
                } catch (e: Exception) {
                    showDecryptedPasswordDialog(
                        "Decrypt failed\nError: ${e.message ?: e::class.java.simpleName}"
                    )
                    return@setOnClickListener
                }
    
                showDecryptPassphraseDialog(
                    encryptedFilePath = encryptedFilePath,
                    privateKeyFile = privateKeyFile
                )
            }
        }
    
        binding.passwordsList.addView(row)
    }

    private fun showDecryptPassphraseDialog(encryptedFilePath: String, privateKeyFile: File) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_decrypt_passphrase, null)
    
        val passphraseInput = dialogView.findViewById<EditText>(R.id.passphrase_input)
        val decryptButton = dialogView.findViewById<Button>(R.id.decrypt_passphrase_decrypt_button)
        val cancelButton = dialogView.findViewById<Button>(R.id.decrypt_passphrase_cancel_button)
    
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
    
        decryptButton.setOnClickListener {
            val passphrase = passphraseInput.text.toString()
    
            dialog.dismiss()
    
            decryptPasswordFile(
                encryptedFilePath = encryptedFilePath,
                privateKeyFile = privateKeyFile,
                passphrase = passphrase
            )
        }
    
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }
    
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
    
        dialog.show()
    }

    private fun decryptPasswordFile(encryptedFilePath: String, privateKeyFile: File, passphrase: String) {
        setLoading(true)
    
        Thread {
            val result = try {
                mainActivity.decryptPasswordFile(
                    privateKeyFile.absolutePath,
                    encryptedFilePath,
                    passphrase
                )
            } catch (e: Exception) {
                "Decrypt failed\nError: ${e.message ?: e::class.java.simpleName}"
            }
    
            activity?.runOnUiThread {
                if (_binding == null) {
                    return@runOnUiThread
                }
    
                setLoading(false)
                showDecryptedPasswordDialog(result)
            }
        }.start()
    }

    private fun showDecryptedPasswordDialog(result: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_decrypted_password, null)
        val titleText = dialogView.findViewById<TextView>(R.id.decrypted_password_title)
        val hiddenText = dialogView.findViewById<TextView>(R.id.decrypted_password_hidden_text)
        val passwordScroll = dialogView.findViewById<ScrollView>(R.id.decrypted_password_scroll)
        val passwordText = dialogView.findViewById<TextView>(R.id.decrypted_password_text)
        val copyButton = dialogView.findViewById<Button>(R.id.decrypted_password_copy_button)
        val showButton = dialogView.findViewById<Button>(R.id.decrypted_password_show_button)
        val closeButton = dialogView.findViewById<Button>(R.id.decrypted_password_close_button)
    
        val decryptFailed =
            result.startsWith("Decrypt failed") ||
                    result.startsWith("ERROR\n") ||
                    result.startsWith("Error:")
    
        passwordText.text = result
        passwordText.setTextColor(rowTextColor())
        hiddenText.setTextColor(rowTextColor())
        titleText.setTextColor(rowTextColor())
    
        if (decryptFailed) {
            titleText.text = "Decrypt Failed"
            hiddenText.visibility = View.GONE
            passwordScroll.visibility = View.VISIBLE
            copyButton.visibility = View.GONE
            showButton.visibility = View.GONE
        } else {
            titleText.text = "Decrypted Password"
            hiddenText.visibility = View.VISIBLE
            passwordScroll.visibility = View.GONE
            copyButton.visibility = View.VISIBLE
            showButton.visibility = View.VISIBLE
        }
    
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
    
        copyButton.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Decrypted Password", result)
            val extras = PersistableBundle()

            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            clip.description.extras = extras
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    
        showButton.setOnClickListener {
            hiddenText.visibility = View.GONE
            passwordScroll.visibility = View.VISIBLE
            showButton.visibility = View.GONE
        }
    
        closeButton.setOnClickListener {
            dialog.dismiss()
        }
    
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
    
        dialog.show()
    }

    private fun resolvePrivateKeyFile(): File {
        val pgpDirectory = File(requireContext().filesDir, "pgp")
        val armoredPrivateKey = File(pgpDirectory, "private-key.asc")
        val binaryPrivateKey = File(pgpDirectory, "private-key.gpg")

        if (armoredPrivateKey.exists() && armoredPrivateKey.isFile && armoredPrivateKey.length() > 0L) {
            return armoredPrivateKey
        }

        if (binaryPrivateKey.exists() && binaryPrivateKey.isFile && binaryPrivateKey.length() > 0L) {
            return binaryPrivateKey
        }

        throw FileNotFoundException(
            "No imported PGP private key found in: ${pgpDirectory.absolutePath}"
        )
    }

    private fun resolveEncryptedPasswordFilePath(displayName: String): String {
        val storagePath = ensureStorageDirectory().getOrThrow()
        val repositoryRoot = File(storagePath, "password-store")

        val encryptedRelativePath =
            if (currentRelativePath.isBlank()) {
                "$displayName.gpg"
            } else {
                "$currentRelativePath/$displayName.gpg"
            }

        return File(repositoryRoot, encryptedRelativePath).absolutePath
    }

    private fun rowBackgroundColor(index: Int): Int {
        return if (index % 2 == 0) {
            android.graphics.Color.rgb(38, 38, 38)
        } else {
            android.graphics.Color.rgb(48, 48, 48)
        }
    }

    private fun rowTextColor(): Int {
        return android.graphics.Color.WHITE
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun refreshInitialState() {
        if (!isGitConfigured()) {
            binding.syncPasswordsButton.visibility = View.GONE
            renderMessage(
                "Git repository configuration is required first.\n\n" +
                        "Open Git Settings and configure Repository URL, Username, and Password."
            )
            return
        }

        binding.syncPasswordsButton.visibility = View.VISIBLE

        val storagePath = ensureStorageDirectory().getOrNull()
        if (storagePath == null) {
            renderMessage("Unable to prepare storage directory.")
            return
        }

        renderRepositoryEntries(storagePath, currentRelativePath)
    }

    private fun isGitConfigured(): Boolean {
        return store.getRepositoryUrl().isNotBlank() &&
                store.getUsername().isNotBlank() &&
                store.hasPassword()
    }

    private fun runLatentAction(action: () -> String) {
        setLoading(true)

        Thread {
            val result = try {
                action()
            } catch (e: Exception) {
                "Operation failed\nError: ${e.message ?: e::class.java.simpleName}"
            }

            activity?.runOnUiThread {
                if (_binding == null) {
                    return@runOnUiThread
                }

                renderRawListResult(result)
                binding.passwordsScroll.post {
                    binding.passwordsScroll.scrollTo(0, 0)
                }
                setLoading(false)
            }
        }.start()
    }

    private fun setLoading(isLoading: Boolean) {
        binding.passwordsScroll.visibility = if (isLoading) View.GONE else View.VISIBLE
        binding.passwordsLoadingBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.syncPasswordsButton.isEnabled = !isLoading
    }

    private fun renderRepositoryEntries(storagePath: String, relativePath: String) {
        val result = mainActivity.listPasswordRepositoryEntries(storagePath, relativePath)
        renderRawListResult(result)
    }

    private fun renderMessage(message: String) {
        binding.passwordsList.removeAllViews()

        val textView = android.widget.TextView(requireContext()).apply {
            text = message
            textSize = 14f
            setPadding(dp(16), 0, dp(16), 16)
            setTextIsSelectable(true)
            setTextColor(rowTextColor())
        }

        binding.passwordsList.addView(textView)
    }

    private fun addPathHeader(path: String) {
        val textView = android.widget.TextView(requireContext()).apply {
            text = "Path: $path"
            textSize = 14f
            setPadding(dp(16), 0, dp(16), dp(12))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(rowTextColor())
        }

        binding.passwordsList.addView(textView)
    }

    private fun syncAndListPasswords(): String {
        val repositoryUrl = store.getRepositoryUrl()
        val username = store.getUsername()
        val password = store.getPassword()

        if (repositoryUrl.isBlank()) {
            return "Sync failed\nRepository URL is empty."
        }

        if (username.isBlank()) {
            return "Sync failed\nUsername is empty."
        }

        if (password.isBlank()) {
            return "Sync failed\nPassword is empty."
        }

        val caPathResult = ensureCaBundle()
        if (caPathResult.isFailure) {
            val error = caPathResult.exceptionOrNull()
            return "Sync failed\nUnable to prepare CA bundle\nError: ${error?.message ?: "unknown error"}"
        }

        val storagePathResult = ensureStorageDirectory()
        if (storagePathResult.isFailure) {
            val error = storagePathResult.exceptionOrNull()
            return "Sync failed\nUnable to prepare storage directory\nError: ${error?.message ?: "unknown error"}"
        }

        val syncResult = mainActivity.syncPasswordRepository(
            caPathResult.getOrThrow(),
            storagePathResult.getOrThrow(),
            repositoryUrl,
            username,
            password
        )

        if (!syncResult.startsWith("Sync succeeded") && !syncResult.startsWith("Clone succeeded")) {
            return syncResult
        }

        currentRelativePath = ""

        return mainActivity.listPasswordRepositoryEntries(
            storagePathResult.getOrThrow(),
            currentRelativePath
        )
    }

    private fun ensureStorageDirectory(): Result<String> {
        val storageDir = File(requireContext().noBackupFilesDir, "repos")

        return try {
            if (!storageDir.exists()) {
                if (!storageDir.mkdirs()) {
                    return Result.failure(
                        IOException("Failed to create storage directory: ${storageDir.absolutePath}")
                    )
                }
            }

            if (!storageDir.isDirectory) {
                return Result.failure(
                    IOException("Storage path exists but is not a directory: ${storageDir.absolutePath}")
                )
            }

            Result.success(storageDir.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun ensureCaBundle(): Result<String> {
        val outFile = File(requireContext().filesDir, "cacert.pem")

        return try {
            if (!outFile.exists()) {
                requireContext().assets.open("cacert.pem").use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            if (!outFile.exists()) {
                return Result.failure(
                    IOException("CA bundle was not created: ${outFile.absolutePath}")
                )
            }

            if (outFile.length() <= 0L) {
                return Result.failure(
                    IOException("CA bundle is empty: ${outFile.absolutePath}")
                )
            }

            Result.success(outFile.absolutePath)
        } catch (e: FileNotFoundException) {
            Result.failure(
                FileNotFoundException("Missing app asset: app/src/main/assets/cacert.pem")
            )
        } catch (e: IOException) {
            Result.failure(
                IOException(
                    "Failed to copy CA bundle to internal storage: ${outFile.absolutePath}",
                    e
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}

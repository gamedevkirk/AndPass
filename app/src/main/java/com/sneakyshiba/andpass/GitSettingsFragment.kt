package com.sneakyshiba.andpass

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.sneakyshiba.andpass.databinding.FragmentGitSettingsBinding

class GitSettingsFragment : Fragment(R.layout.fragment_git_settings) {

    private var _binding: FragmentGitSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: GitSettingsStore

    private val mainActivity: MainActivity
        get() = requireActivity() as MainActivity

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentGitSettingsBinding.bind(view)
        store = GitSettingsStore(requireContext())

        loadSettingsIntoView()

        binding.saveGitSettingsButton.setOnClickListener {
            saveSettings()
        }

        binding.testGitCredentialsButton.setOnClickListener {
            runLatentAction {
                runGitCredentialsTest()
            }
        }

        binding.clearGitSettingsButton.setOnClickListener {
            store.clear()
            loadSettingsIntoView()
            setLoading(false)
            binding.gitSettingsStatus.text = "Git settings cleared."
        }
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
    
                binding.gitSettingsStatus.text = result
                setLoading(false)
            }
        }.start()
    }

    private fun setLoading(isLoading: Boolean) {
        binding.gitSettingsStatus.visibility = if (isLoading) View.GONE else View.VISIBLE
        binding.gitSettingsLoadingBar.visibility = if (isLoading) View.VISIBLE else View.GONE
    
        binding.repositoryUrlInput.isEnabled = !isLoading
        binding.gitUsernameInput.isEnabled = !isLoading
        binding.gitPasswordInput.isEnabled = !isLoading
        binding.saveGitSettingsButton.isEnabled = !isLoading
        binding.testGitCredentialsButton.isEnabled = !isLoading
        binding.clearGitSettingsButton.isEnabled = !isLoading
    }

    private fun loadSettingsIntoView() {
        binding.repositoryUrlInput.setText(store.getRepositoryUrl())
        binding.gitUsernameInput.setText(store.getUsername())

        binding.gitPasswordInput.setText("")

        binding.gitSettingsStatus.text =
            if (store.hasPassword()) {
                "Saved settings loaded.\nA password is stored securely."
            } else {
                "Saved settings loaded.\nNo password is currently stored."
            }
    }

    private fun saveSettings() {
        val repositoryUrl = binding.repositoryUrlInput.text.toString().trim()
        val username = binding.gitUsernameInput.text.toString().trim()
        val password = binding.gitPasswordInput.text.toString()

        store.saveRepositoryUrl(repositoryUrl)
        store.saveUsername(username)

        if (password.isNotEmpty()) {
            store.savePassword(password)
        }

        binding.gitPasswordInput.setText("")

        binding.gitSettingsStatus.text =
            if (store.hasPassword()) {
                "Git settings saved.\nPassword is stored securely."
            } else {
                "Git settings saved.\nNo password was provided."
            }
    }

    private fun runGitCredentialsTest(): String {
        val repositoryUrl = store.getRepositoryUrl()
        val username = store.getUsername()
        val password = store.getPassword()
    
        if (repositoryUrl.isBlank()) {
            return "Git credentials test failed\nRepository URL is empty."
        }
    
        if (username.isBlank()) {
            return "Git credentials test failed\nUsername is empty."
        }
    
        if (password.isBlank()) {
            return "Git credentials test failed\nPassword is empty."
        }
    
        val caPathResult = ensureCaBundle()
    
        return caPathResult.fold(
            onSuccess = { caPath ->
                mainActivity.testGitCredentials(
                    caPath,
                    repositoryUrl,
                    username,
                    password
                )
            },
            onFailure = { error ->
                "Git credentials test failed\n" +
                        "Unable to prepare CA bundle\n" +
                        "Error: ${error.message ?: error::class.java.simpleName}"
            }
        )
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

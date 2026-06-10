package com.sneakyshiba.andpass

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.sneakyshiba.andpass.databinding.FragmentTestsBinding
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class TestsFragment : Fragment(R.layout.fragment_tests) {

    private var _binding: FragmentTestsBinding? = null
    private val binding get() = _binding!!

    private val mainActivity: MainActivity
        get() = requireActivity() as MainActivity

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentTestsBinding.bind(view)

        binding.mainText.text = "Test results will appear here."
        setLoading(false)

        binding.versionInfoButton.setOnClickListener {
            runNativeAction {
                mainActivity.getVersionInfo()
            }
        }

        binding.testHttpsButton.setOnClickListener {
            runNativeAction {
                runHttpsTest()
            }
        }

        binding.testStorageButton.setOnClickListener {
            runNativeAction {
                runStorageTest()
            }
        }

        binding.testGitButton.setOnClickListener {
            runNativeAction {
                runGitTest()
            }
        }

        binding.testPgpButton.setOnClickListener {
            runNativeAction {
                val publicKeyPath = copyAssetToTestFile(
                    assetPath = "pgp-test/public-key.asc",
                    outputName = "public-key.asc"
                )
        
                val privateKeyPath = copyAssetToTestFile(
                    assetPath = "pgp-test/private-key.asc",
                    outputName = "private-key.asc"
                )
        
                val workingDirectory = java.io.File(requireContext().filesDir, "pgp-round-trip-test")
                workingDirectory.mkdirs()
        
                mainActivity.testPgp(
                    publicKeyPath,
                    privateKeyPath,
                    workingDirectory.absolutePath,
                    "foobar"
                )
            }
        }
    }

    private fun runNativeAction(action: () -> String) {
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

                binding.mainText.text = result
                binding.mainScrollBox.post {
                    binding.mainScrollBox.scrollTo(0, 0)
                }
                setLoading(false)
            }
        }.start()
    }

    private fun copyAssetToTestFile(assetPath: String, outputName: String): String {
        val directory = java.io.File(requireContext().filesDir, "pgp-test")
        directory.mkdirs()
    
        val outputFile = java.io.File(directory, outputName)
    
        requireContext().assets.open(assetPath).use { input ->
            outputFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    
        return outputFile.absolutePath
    }

    private fun setLoading(isLoading: Boolean) {
        binding.mainText.visibility = if (isLoading) View.GONE else View.VISIBLE
        binding.loadingBar.visibility = if (isLoading) View.VISIBLE else View.GONE

        binding.versionInfoButton.isEnabled = !isLoading
        binding.testHttpsButton.isEnabled = !isLoading
        binding.testStorageButton.isEnabled = !isLoading
        binding.testGitButton.isEnabled = !isLoading
        binding.testPgpButton.isEnabled = !isLoading
    }

    private fun runHttpsTest(): String {
        val caPathResult = ensureCaBundle()

        return caPathResult.fold(
            onSuccess = { caPath ->
                mainActivity.testHttps(caPath)
            },
            onFailure = { error ->
                "HTTPS test failed\n" +
                        "Unable to prepare CA bundle\n" +
                        "Error: ${error.message ?: error::class.java.simpleName}"
            }
        )
    }

    private fun runStorageTest(): String {
        val storagePathResult = ensureStorageDirectory()

        return storagePathResult.fold(
            onSuccess = { storagePath ->
                mainActivity.testStorage(storagePath)
            },
            onFailure = { error ->
                "Storage test failed\n" +
                        "Unable to prepare storage directory\n" +
                        "Error: ${error.message ?: error::class.java.simpleName}"
            }
        )
    }

    private fun runGitTest(): String {
        val caPathResult = ensureCaBundle()
        val storagePathResult = ensureStorageDirectory()

        if (caPathResult.isFailure) {
            val error = caPathResult.exceptionOrNull()
            return "Git test failed\n" +
                    "Unable to prepare CA bundle\n" +
                    "Error: ${error?.message ?: "unknown error"}"
        }

        if (storagePathResult.isFailure) {
            val error = storagePathResult.exceptionOrNull()
            return "Git test failed\n" +
                    "Unable to prepare storage directory\n" +
                    "Error: ${error?.message ?: "unknown error"}"
        }

        return mainActivity.testGit(
            caPathResult.getOrThrow(),
            storagePathResult.getOrThrow()
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

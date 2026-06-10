package com.sneakyshiba.andpass

import android.app.Dialog
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.Window
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.sneakyshiba.andpass.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.appToolbar)

        if (savedInstanceState == null) {
            showHome()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.menu_navigation -> {
                showNavigationMenu()
                true
            }

            else

            -> {
                super.onOptionsItemSelected(item)
            }
        }

    private fun showHome() {
        supportActionBar?.title = "AndPass"
        supportFragmentManager.beginTransaction().replace(R.id.page_container, HomeFragment()).commit()
    }

    private fun showTests() {
        supportActionBar?.title = "Tests"
        supportFragmentManager.beginTransaction().replace(R.id.page_container, TestsFragment()).commit()
    }

    private fun showNavigationMenu() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_navigation)
        dialog.setCanceledOnTouchOutside(true)

        val homeButton = dialog.findViewById<Button>(R.id.nav_home_button)
        val passwordsButton = dialog.findViewById<Button>(R.id.nav_passwords_button)
        val testsButton = dialog.findViewById<Button>(R.id.nav_tests_button)
        val gitSettingsButton = dialog.findViewById<Button>(R.id.nav_git_settings_button)
        val pgpSettingsButton = dialog.findViewById<Button>(R.id.nav_pgp_settings_button)

        homeButton.setOnClickListener {
            dialog.dismiss()
            showHome()
        }

        passwordsButton.setOnClickListener {
            dialog.dismiss()
            showPasswords()
        }

        testsButton.setOnClickListener {
            dialog.dismiss()
            showTests()
        }

        gitSettingsButton.setOnClickListener {
            dialog.dismiss()
            showGitSettings()
        }

        pgpSettingsButton.setOnClickListener {
            dialog.dismiss()
            showPgpSettings()
        }

        dialog.show()
    }

    private fun showGitSettings() {
        supportActionBar?.title = "Git Settings"
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.page_container, GitSettingsFragment())
            .commit()
    }

    private fun showPasswords() {
        supportActionBar?.title = "Passwords"
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.page_container, PasswordsFragment())
            .commit()
    }

    private fun showPgpSettings() {
        supportActionBar?.title = "PGP Settings"
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.page_container, PgpSettingsFragment())
            .commit()
    }

    external fun getVersionInfo(): String

    external fun testHttps(caPath: String): String

    external fun testStorage(storagePath: String): String

    external fun testGit(
        caPath: String,
        storagePath: String,
    ): String

    external fun testGitCredentials(
        caPath: String,
        repositoryUrl: String,
        username: String,
        password: String,
    ): String

    external fun syncPasswordRepository(
        caPath: String,
        storagePath: String,
        repositoryUrl: String,
        username: String,
        password: String,
    ): String

    external fun listPasswordRepositoryEntries(
        storagePath: String,
        relativePath: String,
    ): String

    external fun getRnpVersionInfo(): String

    external fun testPgp(
        publicKeyPath: String,
        privateKeyPath: String,
        workingDirectoryPath: String,
        passphrase: String,
    ): String

    external fun decryptPasswordFile(
        privateKeyPath: String,
        encryptedFilePath: String,
        passphrase: String,
    ): String

    companion object {
        init {
            System.loadLibrary("andpass")
        }
    }
}

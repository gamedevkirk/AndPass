#include <jni.h>
#include <string>
#include <cstring>
#include <ctime>
#include <cstdio>
#include <algorithm>
#include <vector>

#include <filesystem>
#include <fstream>
#include <sstream>

#include <git2.h>
#include <openssl/crypto.h>
#include <rnp/rnp.h>

namespace fs = std::filesystem;

#define GIT_ERROR(expression) (((expression)) < 0)
#define RNP_ERROR(expression) (((expression)) != 0)

/***********************************************
 * Structs
 ***********************************************/

struct GitCredentialPayload
{
	std::string username;
	std::string password;
};

struct RnpPasswordContext
{
	std::string passphrase;
};

/***********************************************
 * Helper Functions
 ***********************************************/

static std::string read_text_file(const std::string &path)
{
	std::ifstream in(path);
	if (!in)
	{
		return "";
	}

	std::stringstream buffer;
	buffer << in.rdbuf();
	return buffer.str();
}

static std::string git_last_error_message()
{
	const git_error *e = git_error_last();
	return e && e->message ? e->message : "unknown error";
}

static void git_shutdown(git_remote *remote = nullptr, git_repository *repo = nullptr)
{
	if (remote != nullptr)
	{
		git_remote_disconnect(remote);
		git_remote_free(remote);
	}

	if (repo != nullptr)
	{
		git_repository_free(repo);
	}

	git_libgit2_shutdown();
}

static std::string git_shutdown_with_error(const std::string &errorMessage, git_remote *remote = nullptr, git_repository *repo = nullptr)
{
	const git_error *e = git_error_last();
	std::string fullMessage = errorMessage + "\nError: " + (e && e->message ? e->message : "unknown error");

	if (remote != nullptr)
	{
		git_remote_disconnect(remote);
		git_remote_free(remote);
	}

	if (repo != nullptr)
	{
		git_repository_free(repo);
	}

	git_libgit2_shutdown();

	return fullMessage;
}

static bool is_path_inside_dot_git(const fs::path &path)
{
	for (const std::string &part : path)
	{
		if (part == ".git")
		{
			return true;
		}
	}

	return false;
}

static bool jstring_to_string(JNIEnv *env, jstring jstr, std::string &outStr)
{
	if (jstr == nullptr)
	{
		outStr = "Jstring is invalid";
		return false;
	}

	const char *jstrChars = env->GetStringUTFChars(jstr, nullptr);
	if (jstrChars == nullptr)
	{
		env->ReleaseStringUTFChars(jstr, jstrChars);
		outStr = "Unable to read jstring";
		return false;
	}

	outStr = jstrChars;
	env->ReleaseStringUTFChars(jstr, jstrChars);
	return true;
}

/***********************************************
 * Git Functions
 ***********************************************/

static int git_credentials_callback(
  git_credential **out,
  const char *url,
  const char *username_from_url,
  unsigned int allowed_types,
  void *payload
)
{
	GitCredentialPayload *credentials = static_cast<GitCredentialPayload *>(payload);
	const char *username = credentials->username.empty() && username_from_url != nullptr
	                         ? username_from_url
	                         : credentials->username.c_str();

	if ((allowed_types & GIT_CREDENTIAL_USERPASS_PLAINTEXT) == 0)
	{
		return GIT_PASSTHROUGH;
	}

	return git_credential_userpass_plaintext_new(out, username, credentials->password.c_str());
}

static std::string list_repository_entries(std::string &repoPath, std::string &relativePath)
{
	if (!fs::exists(repoPath))
	{
		return "Repository has not been synced yet.\n\nPress Sync to clone it.";
	}

	if (!fs::is_directory(repoPath))
	{
		return "Repository path exists but is not a directory";
	}

	fs::path requestedRelativePath(relativePath);
	for (const std::string &part : requestedRelativePath)
	{
		if (part == "..")
		{
			return "Path attempts to traverse outside of safe dir";
		}
	}

	fs::path targetPath = fs::path(repoPath) / requestedRelativePath;
	if (!fs::exists(targetPath))
	{
		return "Requested path does not exist:\n" + targetPath.string();
	}

	if (!fs::is_directory(targetPath))
	{
		return "Requested path is not a directory:\n" + targetPath.string();
	}

	std::vector<std::string> directories;
	std::vector<std::string> files;
	for (const auto &entry : fs::directory_iterator(targetPath))
	{
		fs::path filename = entry.path().filename();

		if (filename == ".git")
		{
			continue;
		}

		if (entry.is_directory())
		{
			directories.push_back(filename.string());
		}
		else if (entry.is_regular_file())
		{
			if (filename.extension() == ".gpg")
			{
				files.push_back(filename.stem().string());
			}
		}
	}

	std::sort(directories.begin(), directories.end());
	std::sort(files.begin(), files.end());

	if (directories.empty() && files.empty())
	{
		return "Directory is empty.";
	}

	std::string result;

	for (const std::string &directory : directories)
	{
		result += "DIR\t" + directory + "\n";
	}

	for (const std::string &file : files)
	{
		result += "FILE\t" + file + "\n";
	}

	return result;
}

static std::string run_sync_password_repository(
  const std::string &caPath,
  const std::string &storagePath,
  const std::string &repositoryUrl,
  const std::string &username,
  const std::string &password
)
{
	std::string repoPath = storagePath + "/password-store";
	GitCredentialPayload credentialPayload{ username, password };
	git_repository *repo = nullptr;
	git_remote *remote = nullptr;
	git_reference *headRef = nullptr;
	git_reference *remoteBranchRef = nullptr;

	git_libgit2_init();

	if (GIT_ERROR(git_libgit2_opts(GIT_OPT_SET_SSL_CERT_LOCATIONS, caPath.c_str(), nullptr)))
	{
		return git_shutdown_with_error("Sync failed: Unable to configure CA bundle", remote, repo);
	}

	if (!fs::exists(repoPath))
	{
		git_clone_options cloneOptions = GIT_CLONE_OPTIONS_INIT;
		cloneOptions.fetch_opts.callbacks.credentials = git_credentials_callback;
		cloneOptions.fetch_opts.callbacks.payload = &credentialPayload;

		if (GIT_ERROR(git_clone(&repo, repositoryUrl.c_str(), repoPath.c_str(), &cloneOptions)))
		{
			return git_shutdown_with_error("Failed to clone repository:\n" + repositoryUrl, remote, repo);
		}

		return "Clone succeeded";
	}

	if (GIT_ERROR(git_repository_open(&repo, repoPath.c_str())))
	{
		return git_shutdown_with_error("Sync failed\nUnable to open existing repository:\n" + repoPath, remote, repo);
	}

	if (GIT_ERROR(git_remote_lookup(&remote, repo, "origin")))
	{
		return git_shutdown_with_error("Sync failed\nUnable to find origin remote:\n" + repoPath, remote, repo);
	}

	git_fetch_options fetchOptions = GIT_FETCH_OPTIONS_INIT;
	fetchOptions.callbacks.credentials = git_credentials_callback;
	fetchOptions.callbacks.payload = &credentialPayload;
	if (GIT_ERROR(git_remote_fetch(remote, nullptr, &fetchOptions, nullptr)))
	{
		return git_shutdown_with_error("Fetch failed\n" + repositoryUrl, remote, repo);
	}

	if (GIT_ERROR(git_repository_head(&headRef, repo)))
	{
		return git_shutdown_with_error("Local HEAD could not be resolved\n" + repoPath, remote, repo);
	}

	const char *branchName = git_reference_shorthand(headRef);
	std::string remoteBranchRefName = "refs/remotes/origin/" + std::string(branchName);
	if (GIT_ERROR(git_reference_lookup(&remoteBranchRef, repo, remoteBranchRefName.c_str())))
	{
		git_reference_free(headRef);
		return git_shutdown_with_error("Remote branch could not be resolved\n" + remoteBranchRefName, remote, repo);
	}

	const git_oid *targetOid = git_reference_target(remoteBranchRef);
	git_object *targetObject = nullptr;
	if (GIT_ERROR(git_object_lookup(&targetObject, repo, targetOid, GIT_OBJECT_COMMIT)))
	{
		git_reference_free(remoteBranchRef);
		git_reference_free(headRef);
		return git_shutdown_with_error("Remote commit could not be loaded", remote, repo);
	}

	git_checkout_options checkoutOptions = GIT_CHECKOUT_OPTIONS_INIT;
	checkoutOptions.checkout_strategy = GIT_CHECKOUT_SAFE;
	if (GIT_ERROR(git_checkout_tree(repo, targetObject, &checkoutOptions)))
	{
		git_object_free(targetObject);
		git_reference_free(remoteBranchRef);
		git_reference_free(headRef);
		return git_shutdown_with_error("Checkout failed", remote, repo);
	}

	if (GIT_ERROR(git_reference_set_target(&headRef, headRef, targetOid, "Fast-forward")))
	{
		git_object_free(targetObject);
		git_reference_free(remoteBranchRef);
		git_reference_free(headRef);
		return git_shutdown_with_error("HEAD update failed", remote, repo);
	}

	git_object_free(targetObject);
	git_reference_free(remoteBranchRef);
	git_reference_free(headRef);
	git_shutdown();

	return "Sync succeeded";
}

/***********************************************
 * RNP
 ***********************************************/

static bool rnp_password_provider(rnp_ffi_t ffi, void *app_ctx, rnp_key_handle_t key, const char *pgp_context, char buf[], size_t buf_len)
{
	if (app_ctx == nullptr || buf == nullptr || buf_len == 0)
	{
		return false;
	}

	RnpPasswordContext *context = static_cast<RnpPasswordContext *>(app_ctx);
	if (context->passphrase.empty())
	{
		return false;
	}

	std::strncpy(buf, context->passphrase.c_str(), buf_len - 1);
	buf[buf_len - 1] = '\0';

	return true;
}

/***********************************************
 * Version Reporting
 ***********************************************/

static std::string get_libgit2_version_string()
{
	int gitMajor = 0;
	int gitMinor = 0;
	int gitRev = 0;

	git_libgit2_version(&gitMajor, &gitMinor, &gitRev);
	return std::to_string(gitMajor) + "." + std::to_string(gitMinor) + "." + std::to_string(gitRev);
}

static std::string get_version_info()
{
	git_libgit2_init();
	std::string result = "libgit2 version: " + get_libgit2_version_string() +
	                     "\nOpenSSL version: " + OpenSSL_version(OPENSSL_VERSION);

	git_shutdown();
	return result;
}

/***********************************************
 * Tests
 ***********************************************/

static std::string run_storage_test(const std::string &storagePath)
{
	std::string filePath = storagePath + "/andpass-storage-test.txt";
	std::string expectedContents = "AndPass storage test";

	std::ofstream out(filePath, std::ios::out | std::ios::trunc);
	if (!out)
	{
		return "Storage test failed\n"
		       "Unable to open file for writing\n"
		       "Path: " +
		       filePath;
	}

	out << expectedContents;
	if (!out)
	{
		return "Storage test failed\n"
		       "Failed while writing file\n"
		       "Path: " +
		       filePath;
	}

	out.close();

	std::ifstream in(filePath);
	if (!in)
	{
		return "Storage test failed\n"
		       "Unable to open file for reading\n"
		       "Path: " +
		       filePath;
	}

	std::stringstream buffer;
	buffer << in.rdbuf();
	std::string actualContents = buffer.str();
	if (actualContents != expectedContents)
	{
		std::remove(filePath.c_str());
		return "Storage test failed\n"
		       "Read contents did not match written contents\n"
		       "Path: " +
		       filePath + "\nExpected:\n" + expectedContents + "\nActual:\n" + actualContents;
	}

	if (std::remove(filePath.c_str()) != 0)
	{
		return "Storage test failed\n"
		       "Write/read succeeded, but delete failed\n"
		       "Path: " +
		       filePath;
	}

	return "Storage test succeeded\n";
}

static std::string run_libgit2_https_test(const std::string &caPath)
{
	const char *url = "https://github.com/gamedevkirk/gamedevkirk";
	git_remote *remote = nullptr;

	git_libgit2_init();

	if (GIT_ERROR(git_libgit2_opts(GIT_OPT_SET_SSL_CERT_LOCATIONS, caPath.c_str(), nullptr)))
	{
		return git_shutdown_with_error("Failed to configure CA bundle\n", remote);
	}

	if (GIT_ERROR(git_remote_create_anonymous(&remote, nullptr, url)))
	{
		return git_shutdown_with_error("git_remote_create_anonymous failed", remote);
	}

	git_remote_callbacks callbacks = GIT_REMOTE_CALLBACKS_INIT;
	if (GIT_ERROR(git_remote_connect(remote, GIT_DIRECTION_FETCH, &callbacks, nullptr, nullptr)))
	{
		return git_shutdown_with_error("git_remote_connect failed", remote);
	}

	const git_remote_head **refs = nullptr;
	size_t refs_len = 0;
	if (GIT_ERROR(git_remote_ls(&refs, &refs_len, remote)))
	{
		return git_shutdown_with_error("git_remote_ls failed", remote);
	}

	git_shutdown(remote);
	return "HTTPS test succeeded";
}

static std::string run_git_test(const std::string &caPath, const std::string &storagePath)
{
	std::string url = "https://github.com/gamedevkirk/gamedevkirk.git";
	std::string repoPath = storagePath + "/test-repo";
	std::string readmePath = repoPath + "/README.md";
	git_repository *repo = nullptr;

	if (fs::exists(repoPath))
	{
		fs::remove_all(repoPath);
		if (fs::exists(repoPath))
		{
			return "Git test failed\n"
			       "Repository already exists and was unable to be deleted";
		}
	}

	git_libgit2_init();

	if (GIT_ERROR(git_libgit2_opts(GIT_OPT_SET_SSL_CERT_LOCATIONS, caPath.c_str(), nullptr)))
	{
		return git_shutdown_with_error("Git test failed\nUnable to configure CA bundle", nullptr, repo);
	}

	git_clone_options cloneOptions = GIT_CLONE_OPTIONS_INIT;
	if (GIT_ERROR(git_clone(&repo, url.c_str(), repoPath.c_str(), &cloneOptions)))
	{
		return git_shutdown_with_error("Git test failed", nullptr, repo);
	}

	if (!fs::exists(readmePath))
	{
		return git_shutdown_with_error("Git test failed\nRepository cloned, but README.md was not found", nullptr, repo);
	}

	std::string readmeContents = read_text_file(readmePath);
	if (readmeContents.empty())
	{
		return git_shutdown_with_error("Git test failed\nRepository cloned, but README.md was empty or unreadable", nullptr, repo);
	}

	std::error_code ec;
	fs::remove_all(repoPath, ec);
	if (ec)
	{
		return git_shutdown_with_error("Git test failed when deleting repo.\n" + ec.message(), nullptr, repo);
	}

	git_shutdown();
	return "Git test succeeded";
}

static std::string run_git_credentials_test(
  const std::string &caPath,
  const std::string &repositoryUrl,
  const std::string &username,
  const std::string &password
)
{
	GitCredentialPayload credentialPayload{ username, password };
	git_remote *remote = nullptr;
	const git_remote_head **refs = nullptr;

	git_libgit2_init();

	if (GIT_ERROR(git_libgit2_opts(GIT_OPT_SET_SSL_CERT_LOCATIONS, caPath.c_str(), nullptr)))
	{
		return git_shutdown_with_error("Git credentials test failed\nUnable to configure CA bundle", remote);
	}

	if (GIT_ERROR(git_remote_create_anonymous(&remote, nullptr, repositoryUrl.c_str())))
	{
		return git_shutdown_with_error("Git credentials test failed\nUnable to create anonymous remote", remote);
	}

	git_remote_callbacks callbacks = GIT_REMOTE_CALLBACKS_INIT;
	callbacks.credentials = git_credentials_callback;
	callbacks.payload = &credentialPayload;
	if (GIT_ERROR(git_remote_connect(remote, GIT_DIRECTION_FETCH, &callbacks, nullptr, nullptr)))
	{
		return git_shutdown_with_error("Git credentials test failed\nUnable to connect to remote", remote);
	}

	size_t refs_len = 0;
	if (GIT_ERROR(git_remote_ls(&refs, &refs_len, remote)))
	{
		return git_shutdown_with_error("Git credentials test failed\nConnected, but unable to list remote refs", remote);
	}

	std::string firstRef = refs_len > 0 && refs[0] && refs[0]->name ? refs[0]->name : "(none)";
	std::string result = "Git credentials test succeeded\n" + repositoryUrl + "\nUsername: " + username;

	git_shutdown();
	return result;
}

/***********************************************
 * JNI Functions
 ***********************************************/

extern "C" JNIEXPORT jstring JNICALL Java_com_sneakyshiba_andpass_MainActivity_getVersionInfo(JNIEnv *env, jobject /* this */)
{
	std::ostringstream output;

	output << "libgit2 version: " << LIBGIT2_VER_MAJOR << "." << LIBGIT2_VER_MINOR << "."
	       << LIBGIT2_VER_REVISION << "\n";

	output << "OpenSSL version: " << OpenSSL_version(OPENSSL_VERSION) << "\n";

	const char *rnpVersion = rnp_version_string();
	output << "RNP version: " << (rnpVersion != nullptr ? rnpVersion : "unknown") << "\n";

	return env->NewStringUTF(output.str().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sneakyshiba_andpass_MainActivity_testStorage(JNIEnv *env, jobject /* this */, jstring storagePathJString)
{
	std::string storagePath;
	if (!jstring_to_string(env, storagePathJString, storagePath))
	{
		return env->NewStringUTF("Storage test failed\nStorage path was invalid");
	}

	std::string result = run_storage_test(storagePath);
	return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sneakyshiba_andpass_MainActivity_testHttps(JNIEnv *env, jobject /* this */, jstring caPathJString)
{
	std::string caPath;
	if (!jstring_to_string(env, caPathJString, caPath))
	{
		return env->NewStringUTF("HTTPS test failed\nCA path was invalid");
	}

	std::string result = run_libgit2_https_test(caPath);
	return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sneakyshiba_andpass_MainActivity_testGit(JNIEnv *env, jobject /* this */, jstring caPathJString, jstring storagePathJString)
{
	std::string caPath;
	if (!jstring_to_string(env, caPathJString, caPath))
	{
		return env->NewStringUTF("Git test failed\nCA path was invalid");
	}

	std::string storagePath;
	if (!jstring_to_string(env, storagePathJString, storagePath))
	{
		return env->NewStringUTF("Git test failed\nStorage path was invalid");
	}

	std::string result = run_git_test(caPath, storagePath);
	return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_com_sneakyshiba_andpass_MainActivity_testGitCredentials(
  JNIEnv *env,
  jobject /* this */,
  jstring caPathJString,
  jstring repositoryUrlJString,
  jstring usernameJString,
  jstring passwordJString
)
{
	std::string caPath;
	if (!jstring_to_string(env, caPathJString, caPath))
	{
		return env->NewStringUTF("Git credentials test failed\nCA path was invalid");
	}

	std::string repositoryUrl;
	if (!jstring_to_string(env, repositoryUrlJString, repositoryUrl))
	{
		return env->NewStringUTF("Git credentials test failed\nRepository URL was invalid");
	}

	std::string username;
	if (!jstring_to_string(env, usernameJString, username))
	{
		return env->NewStringUTF("Git credentials test failed\nUsername was invalid");
	}

	std::string password;
	if (!jstring_to_string(env, passwordJString, password))
	{
		return env->NewStringUTF("Git credentials test failed\nPassword was invalid");
	}

	std::string result = run_git_credentials_test(caPath, repositoryUrl, username, password);
	return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_com_sneakyshiba_andpass_MainActivity_syncPasswordRepository(
  JNIEnv *env,
  jobject /* this */,
  jstring caPathJString,
  jstring storagePathJString,
  jstring repositoryUrlJString,
  jstring usernameJString,
  jstring passwordJString
)
{
	std::string caPath;
	if (!jstring_to_string(env, caPathJString, caPath))
	{
		return env->NewStringUTF("Sync failed\nCA Path was invalid");
	}

	std::string storagePath;
	if (!jstring_to_string(env, storagePathJString, storagePath))
	{
		return env->NewStringUTF("Sync failed\nStorage Path was invalid");
	}

	std::string repositoryUrl;
	if (!jstring_to_string(env, repositoryUrlJString, repositoryUrl))
	{
		return env->NewStringUTF("Sync failed\nRepository URL was invalid");
	}

	std::string username;
	if (!jstring_to_string(env, usernameJString, username))
	{
		return env->NewStringUTF("Sync failed\nUsername was invalid");
	}

	std::string password;
	if (!jstring_to_string(env, passwordJString, password))
	{
		return env->NewStringUTF("Sync failed\nPassword was invalid");
	}

	std::string result = run_sync_password_repository(caPath, storagePath, repositoryUrl, username, password);
	return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_com_sneakyshiba_andpass_MainActivity_listPasswordRepositoryEntries(
  JNIEnv *env,
  jobject /* this */,
  jstring storagePathJString,
  jstring relativePathJString
)
{
	std::string storagePath;
	if (!jstring_to_string(env, storagePathJString, storagePath))
	{
		return env->NewStringUTF("List repository entries failed\nCA path was invalid");
	}

	std::string relativePath;
	if (!jstring_to_string(env, relativePathJString, relativePath))
	{
		return env->NewStringUTF("List repository entries failed\nRelative path was invalid");
	}

	std::string repoPath = storagePath + "/password-store";
	std::string result = list_repository_entries(repoPath, relativePath);
	return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_com_sneakyshiba_andpass_MainActivity_testPgp(
  JNIEnv *env,
  jobject /* this */,
  jstring publicKeyPathJString,
  jstring privateKeyPathJString,
  jstring workingDirectoryPathJString,
  jstring passphraseJString
)
{
	std::string publicKeyPath;
	if (!jstring_to_string(env, publicKeyPathJString, publicKeyPath))
	{
		return env->NewStringUTF("PGP test failed\nPublic Key Path was invalid");
	}

	std::string privateKeyPath;
	if (!jstring_to_string(env, privateKeyPathJString, privateKeyPath))
	{
		return env->NewStringUTF("PGP test failed\nPrivate Key Path was invalid");
	}

	std::string workingDirectoryPath;
	if (!jstring_to_string(env, workingDirectoryPathJString, workingDirectoryPath))
	{
		return env->NewStringUTF("PGP test failed\nWorking Directory Path was invalid");
	}

	std::string passphrase;
	if (!jstring_to_string(env, passphraseJString, passphrase))
	{
		return env->NewStringUTF("PGP test failed\nPassphrase was invalid");
	}

	std::string plaintext = "andpass-pgp-round-trip-test-password\n";
	std::string recipientFingerprint = "BEB4E33FF23B58FECE21DED29C86F89BA00C12A2";
	std::string plaintextPath = workingDirectoryPath + "/test-password.txt";
	std::string encryptedPath = workingDirectoryPath + "/test-password.gpg";

	std::ostringstream output;

	rnp_ffi_t ffi = nullptr;
	rnp_input_t publicKeyInput = nullptr;
	rnp_input_t privateKeyInput = nullptr;
	rnp_input_t plaintextInput = nullptr;
	rnp_input_t encryptedInput = nullptr;
	rnp_output_t encryptedOutput = nullptr;
	rnp_output_t decryptedOutput = nullptr;
	rnp_op_encrypt_t encryptOperation = nullptr;
	rnp_key_handle_t recipientKey = nullptr;

	uint8_t *decryptedBuffer = nullptr;
	size_t decryptedBufferLength = 0;

	RnpPasswordContext passwordContext;
	passwordContext.passphrase = passphrase;

	std::remove(plaintextPath.c_str());
	std::remove(encryptedPath.c_str());

	{
		std::ofstream plaintextFile(plaintextPath, std::ios::binary);
		if (!plaintextFile)
		{
			output << "PGP test failed.\n\nUnable to create plaintext test file:\n"
			       << plaintextPath;
			goto finish;
		}

		plaintextFile << plaintext;
	}

	if (rnp_ffi_create(&ffi, "GPG", "GPG") != 0)
	{
		output << "PGP test failed.\n\nUnable to initialize RNP.";
		goto finish;
	}

	if (rnp_input_from_path(&publicKeyInput, publicKeyPath.c_str()) != 0)
	{
		output << "PGP test failed.\n\nUnable to open public key:\n" << publicKeyPath;
		goto finish;
	}

	if (rnp_load_keys(ffi, "GPG", publicKeyInput, RNP_LOAD_SAVE_PUBLIC_KEYS) != 0)
	{
		output << "PGP test failed.\n\nUnable to load public key.";
		goto finish;
	}

	if (rnp_input_from_path(&privateKeyInput, privateKeyPath.c_str()) != 0)
	{
		output << "PGP test failed.\n\nUnable to open private key:\n" << privateKeyPath;
		goto finish;
	}

	if (rnp_load_keys(ffi, "GPG", privateKeyInput, RNP_LOAD_SAVE_SECRET_KEYS) != 0)
	{
		output << "PGP test failed.\n\nUnable to load private key.";
		goto finish;
	}

	rnp_ffi_set_pass_provider(ffi, rnp_password_provider, &passwordContext);

	if (rnp_input_from_path(&plaintextInput, plaintextPath.c_str()) != 0)
	{
		output << "PGP test failed.\n\nUnable to open plaintext test file:\n" << plaintextPath;
		goto finish;
	}

	if (rnp_output_to_path(&encryptedOutput, encryptedPath.c_str()) != 0)
	{
		output << "PGP test failed.\n\nUnable to create encrypted test file:\n" << encryptedPath;
		goto finish;
	}

	if (rnp_op_encrypt_create(&encryptOperation, ffi, plaintextInput, encryptedOutput) != 0)
	{
		output << "PGP test failed.\n\nUnable to create encryption operation.";
		goto finish;
	}

	rnp_op_encrypt_set_armor(encryptOperation, false);
	rnp_op_encrypt_set_file_name(encryptOperation, "test-password.txt");
	rnp_op_encrypt_set_file_mtime(encryptOperation, static_cast<uint32_t>(std::time(nullptr)));
	rnp_op_encrypt_set_compression(encryptOperation, "ZIP", 6);
	rnp_op_encrypt_set_cipher(encryptOperation, RNP_ALGNAME_AES_256);
	rnp_op_encrypt_set_aead(encryptOperation, "None");

	if (rnp_locate_key(ffi, "fingerprint", recipientFingerprint.c_str(), &recipientKey) != 0)
	{
		output << "PGP test failed.\n\nUnable to locate recipient key fingerprint:\n"
		       << recipientFingerprint;
		goto finish;
	}

	if (rnp_op_encrypt_add_recipient(encryptOperation, recipientKey) != 0)
	{
		output << "PGP test failed.\n\nUnable to add recipient key fingerprint:\n"
		       << recipientFingerprint;
		goto finish;
	}

	if (rnp_op_encrypt_execute(encryptOperation) != 0)
	{
		output << "PGP test failed.\n\nEncryption failed.";
		goto finish;
	}

	if (rnp_input_from_path(&encryptedInput, encryptedPath.c_str()) != 0)
	{
		output << "PGP test failed.\n\nUnable to open encrypted test file:\n" << encryptedPath;
		goto finish;
	}

	if (rnp_output_to_memory(&decryptedOutput, 0) != 0)
	{
		output << "PGP test failed.\n\nUnable to create decrypted output.";
		goto finish;
	}

	if (rnp_decrypt(ffi, encryptedInput, decryptedOutput) != 0)
	{
		output << "PGP test failed.\n\nDecryption failed.";
		goto finish;
	}

	if (rnp_output_memory_get_buf(decryptedOutput, &decryptedBuffer, &decryptedBufferLength, false) != 0)
	{
		output << "PGP test failed.\n\nUnable to read decrypted output.";
		goto finish;
	}

	{
		output << "PGP test tests succeeded.";
	}

finish:
	rnp_key_handle_destroy(recipientKey);
	rnp_op_encrypt_destroy(encryptOperation);
	rnp_input_destroy(publicKeyInput);
	rnp_input_destroy(privateKeyInput);
	rnp_input_destroy(plaintextInput);
	rnp_input_destroy(encryptedInput);
	rnp_output_destroy(encryptedOutput);
	rnp_output_destroy(decryptedOutput);
	rnp_ffi_destroy(ffi);

	return env->NewStringUTF(output.str().c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_com_sneakyshiba_andpass_MainActivity_decryptPasswordFile(
  JNIEnv *env,
  jobject /* thiz */,
  jstring privateKeyPathJString,
  jstring encryptedFilePathJString,
  jstring passphraseJString
)
{
	uint8_t *decryptedBuffer = nullptr;
	size_t decryptedBufferLength = 0;
	rnp_result_t result = {};

	std::string privateKeyPath;
	if (!jstring_to_string(env, privateKeyPathJString, privateKeyPath))
	{
		return env->NewStringUTF("Decrypt password file failed\nPrivate Key Path was invalid");
	}

	std::string encryptedFilePath;
	if (!jstring_to_string(env, encryptedFilePathJString, encryptedFilePath))
	{
		return env->NewStringUTF("Decrypt password file failed\nEncrypted File Path was invalid");
	}

	std::string passphrase;
	if (!jstring_to_string(env, passphraseJString, passphrase))
	{
		return env->NewStringUTF("Decrypt password file failed\nPassphrase was invalid");
	}

	std::ostringstream output;
	rnp_ffi_t ffi = nullptr;
	rnp_input_t privateKeyInput = nullptr;
	rnp_input_t encryptedInput = nullptr;
	rnp_output_t decryptedOutput = nullptr;

	RnpPasswordContext passwordContext;
	passwordContext.passphrase = passphrase;

	if (RNP_ERROR(rnp_ffi_create(&ffi, "GPG", "GPG")))
	{
		output << "Decrypt failed.\n\nUnable to create RNP FFI.";
		return env->NewStringUTF(output.str().c_str());
	}

	if (RNP_ERROR(rnp_input_from_path(&privateKeyInput, privateKeyPath.c_str())))
	{
		output << "Decrypt failed.\n\nUnable to open private key file:\n" << privateKeyPath;
		goto finish;
	}

	if (RNP_ERROR(rnp_load_keys(ffi, "GPG", privateKeyInput, RNP_LOAD_SAVE_SECRET_KEYS)))
	{
		output << "Decrypt failed.\n\nUnable to load private key file:\n" << privateKeyPath;
		goto finish;
	}

	rnp_ffi_set_pass_provider(ffi, rnp_password_provider, &passwordContext);

	if (RNP_ERROR(rnp_input_from_path(&encryptedInput, encryptedFilePath.c_str())))
	{
		output << "Decrypt failed.\n\nUnable to open encrypted password file:\n"
		       << encryptedFilePath;
		goto finish;
	}

	if (RNP_ERROR(rnp_output_to_memory(&decryptedOutput, 0)))
	{
		output << "Decrypt failed.\n\nUnable to create decrypted memory output.";
		goto finish;
	}

	if (RNP_ERROR(rnp_decrypt(ffi, encryptedInput, decryptedOutput)))
	{
		output << "Decrypt failed.\n\nLikely bad password supplied";
		goto finish;
	}

	result = rnp_output_memory_get_buf(decryptedOutput, &decryptedBuffer, &decryptedBufferLength, false);
	if (result != 0 || decryptedBuffer == nullptr)
	{
		output << "Decrypt failed.\n\nUnable to read decrypted memory output.";
		goto finish;
	}

	output << std::string(reinterpret_cast<char *>(decryptedBuffer), decryptedBufferLength);

finish:
	if (decryptedOutput != nullptr)
	{
		rnp_output_destroy(decryptedOutput);
	}

	if (encryptedInput != nullptr)
	{
		rnp_input_destroy(encryptedInput);
	}

	if (privateKeyInput != nullptr)
	{
		rnp_input_destroy(privateKeyInput);
	}

	if (ffi != nullptr)
	{
		rnp_ffi_destroy(ffi);
	}

	return env->NewStringUTF(output.str().c_str());
}

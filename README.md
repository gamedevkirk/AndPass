# AndPass

A password manager for Android that utilizes OpenPGP and Git to provide a "[Pass](https://www.passwordstore.org/)"-like password manager on the go.

> [!WARNING]
> This project is a work in progress. Basic red-only functionality is present, but much of the systems are still untested.
> If you encounter problems at runtime or with building the project, please open an issue and I'll address it if reasonably possible.

For information on setting up a password repository with Git + PGP, see [this article](https://gist.github.com/abtrout/d64fb11ad6f9f49fa325).

## Building the Project

This repo is highly dependent on submodules. Before building, ensure you've properly cloned all required submodules.

`git submodule update --init --recursive`

There are helper scripts setup at the root of the project for building each of the submodules for Android. As long as you've setup your Android-related environment variables, these should work out of the box.

Module Compilation Order:

1. openssl
2. bzip2
3. json-c
4. libgit2
5. rnp
6. andpass


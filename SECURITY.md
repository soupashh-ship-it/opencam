# Security and signing notes

## Required action for an existing repository

The supplied fixed-source archive contains no keystore, `keystore.properties`,
or Git history. If a plaintext signing password or a release keystore was ever
committed to an earlier repository revision, removing the current file is not
enough: treat that credential as compromised.

Before publishing another release:

1. create a new release keystore or rotate the signing credentials according to
   the distribution channel's key-management process;
2. replace the CI secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`
   and `KEY_PASSWORD`;
3. remove the exposed file and value from repository history using an
   appropriate history-rewrite tool, then coordinate the force-push with all
   collaborators;
4. revoke or retire the old key wherever the store/distribution mechanism
   allows it;
5. verify the release APK certificate fingerprint before publication.

Do not copy signing material into the project archive. The Gradle release task
and CI workflow intentionally fail when complete signing configuration is not
present.

## Network model

The stream protocol remains unauthenticated for compatibility with the desktop
plugin. A client that can reach the app's TCP port can request camera/audio
streams. Use trusted networks, firewall the port where appropriate, and stop
the foreground service when streaming is complete.

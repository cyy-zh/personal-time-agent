# Release signing

Release signing material is private and must never be committed, uploaded to GitHub, attached to CI logs, or shared in chat. The keystore and its password backup are kept outside this repository. Keep at least one offline backup of both files in separate protected storage; losing the keystore prevents updates signed as the same app identity. Do not place the password beside the keystore in plaintext.

The local release build reads these environment variables (the Gradle configuration must be present before producing a release build):

```text
PTA_KEYSTORE       path to the private PKCS12/JKS keystore
PTA_STORE_PASSWORD keystore password
PTA_KEY_ALIAS      signing key alias
PTA_KEY_PASSWORD   private key password
```

Set the values only in the local shell or a trusted secret manager. Avoid putting credentials in project properties, command-line arguments, shell history, screenshots, or build logs. A CI release workflow should use encrypted repository/environment secrets, but this repository does not publish releases automatically.

On Windows, DPAPI-protected password backups can be decrypted only by the same Windows user on the same machine context. Before relying on one, verify that the encrypted backup can be restored; keep a separately protected offline recovery copy for migration. Do not commit DPAPI blobs either.

The checked-in Gradle workflow builds and checks debug variants only. It must not sign or publish release artifacts.

package com.streamarr.server.services.streaming.trust;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class CertificateAuthorityStore {

  private static final String ROOT_ALIAS = "streamarr-root";
  private static final String ISSUER_ALIAS = "streamarr-issuer";
  private static final String REVOCATION_SIGNER_ALIAS = "streamarr-revocation-signer";
  private static final Set<String> REQUIRED_ALIASES =
      Set.of(ROOT_ALIAS, ISSUER_ALIAS, REVOCATION_SIGNER_ALIAS);
  private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");
  private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS =
      PosixFilePermissions.fromString("rwx------");
  private static final byte[] KEY_MATCH_CHALLENGE =
      "streamarr-ca-key-pair-validation-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  private static final String INVALID_SPIFFE_IDENTITY =
      "Certificate authority root has an invalid SPIFFE trust-domain identity";

  private final Path secretPath;
  private final MacOsExtendedAclValidator macOsAclValidator;
  private final PosixOwnerValidator posixOwnerValidator;

  public CertificateAuthorityStore(Path secretPath) {
    this.macOsAclValidator = new MacOsExtendedAclValidator();
    this.posixOwnerValidator = new PosixOwnerValidator();
    this.secretPath =
        new CertificateAuthorityPathResolver(posixOwnerValidator, macOsAclValidator)
            .resolve(secretPath);
  }

  public Optional<CertificateAuthorityMaterial> load() {
    requireProtectedAncestors();
    if (!Files.exists(secretPath.getParent(), LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    requireSecureParent();
    if (!Files.exists(secretPath, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    requireSecureRegularFile();

    try {
      forceDirectory(secretPath.getParent());
      try (var channel =
              FileChannel.open(secretPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
          var input = Channels.newInputStream(channel)) {
        var keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(input, password());
        requireExactAliases(keyStore);

        var root = keyMaterial(keyStore, ROOT_ALIAS);
        var issuer = keyMaterial(keyStore, ISSUER_ALIAS);
        var revocationSigner = keyMaterial(keyStore, REVOCATION_SIGNER_ALIAS);
        requireChain(keyStore, ROOT_ALIAS, root.certificate());
        requireChain(keyStore, ISSUER_ALIAS, issuer.certificate(), root.certificate());
        requireChain(
            keyStore,
            REVOCATION_SIGNER_ALIAS,
            revocationSigner.certificate(),
            issuer.certificate(),
            root.certificate());
        requireMatchingKey(root);
        requireMatchingKey(issuer);
        requireMatchingKey(revocationSigner);

        return Optional.of(
            new CertificateAuthorityMaterial(
                installationId(root.certificate()),
                new CertificateAuthorityMaterial.AuthorityChainMaterial(
                    root, issuer, revocationSigner)));
      }
    } catch (CertificateAuthorityStoreException e) {
      throw e;
    } catch (IOException
        | GeneralSecurityException
        | IllegalArgumentException
        | ClassCastException e) {
      throw new CertificateAuthorityStoreException(
          "Failed to read certificate authority secret at " + secretPath, e);
    }
  }

  public CertificateAuthorityMaterial createIfAbsent(CertificateAuthorityMaterial material) {
    if (material == null) {
      throw new CertificateAuthorityStoreException("Certificate authority material is required");
    }
    var existing = load();
    if (existing.isPresent()) {
      return existing.orElseThrow();
    }
    requireSecureParent();
    requireDurableParentEntry();

    Path temporary = null;
    try {
      temporary = createTemporaryFile();
      writeKeyStore(temporary, material);
      requirePublicationMatches(temporary, material);
      try {
        Files.createLink(secretPath, temporary);
      } catch (FileAlreadyExistsException _) {
        Files.deleteIfExists(temporary);
        return load()
            .orElseThrow(
                () ->
                    new CertificateAuthorityStoreException(
                        "Certificate authority publication raced without a readable winner"));
      } catch (UnsupportedOperationException e) {
        throw new CertificateAuthorityStoreException(
            "Certificate authority path must support atomic hard-link publication", e);
      }
      Files.delete(temporary);
      return load()
          .orElseThrow(
              () ->
                  new CertificateAuthorityStoreException(
                      "Certificate authority publication did not create the secret"));
    } catch (CertificateAuthorityStoreException e) {
      throw e;
    } catch (IOException | GeneralSecurityException e) {
      throw new CertificateAuthorityStoreException(
          "Failed to persist certificate authority secret at " + secretPath, e);
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException _) {
          // A complete orphaned hard link is harmless and ignored on the next bootstrap.
        }
      }
    }
  }

  private void writeKeyStore(Path path, CertificateAuthorityMaterial material)
      throws GeneralSecurityException, IOException {
    var keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(null, password());
    keyStore.setKeyEntry(
        ROOT_ALIAS,
        material.rootPrivateKey(),
        password(),
        new Certificate[] {material.rootCertificate()});
    keyStore.setKeyEntry(
        ISSUER_ALIAS,
        material.issuerPrivateKey(),
        password(),
        new Certificate[] {material.issuerCertificate(), material.rootCertificate()});
    keyStore.setKeyEntry(
        REVOCATION_SIGNER_ALIAS,
        material.revocationSignerPrivateKey(),
        password(),
        new Certificate[] {
          material.revocationSignerCertificate(),
          material.issuerCertificate(),
          material.rootCertificate()
        });

    try (var channel =
            FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        var output = Channels.newOutputStream(channel)) {
      keyStore.store(output, password());
      output.flush();
      channel.force(true);
    }
  }

  private void requirePublicationMatches(
      Path temporary, CertificateAuthorityMaterial expectedMaterial) {
    var prepared =
        new CertificateAuthorityStore(temporary)
            .load()
            .orElseThrow(
                () ->
                    new CertificateAuthorityStoreException(
                        "Prepared certificate authority secret is unreadable"));
    if (!prepared.installationId().equals(expectedMaterial.installationId())) {
      throw new CertificateAuthorityStoreException(
          "Certificate authority installation identity does not match its root certificate");
    }
  }

  private CertificateAuthorityMaterial.AuthorityKeyMaterial keyMaterial(
      KeyStore keyStore, String alias) throws GeneralSecurityException {
    var key = keyStore.getKey(alias, password());
    var certificate = keyStore.getCertificate(alias);
    if (!(key instanceof PrivateKey privateKey)
        || !(certificate instanceof X509Certificate x509Certificate)) {
      throw new CertificateAuthorityStoreException(
          "Certificate authority alias has no private key and X.509 certificate: " + alias);
    }
    return new CertificateAuthorityMaterial.AuthorityKeyMaterial(privateKey, x509Certificate);
  }

  private void requireChain(KeyStore keyStore, String alias, X509Certificate... expected)
      throws GeneralSecurityException {
    var actual =
        Optional.ofNullable(keyStore.getCertificateChain(alias)).orElse(new Certificate[0]);
    if (actual.length != expected.length) {
      throw new CertificateAuthorityStoreException(
          "Certificate authority alias has an invalid chain: " + alias);
    }
    for (int index = 0; index < expected.length; index++) {
      if (!MessageDigest.isEqual(actual[index].getEncoded(), expected[index].getEncoded())) {
        throw new CertificateAuthorityStoreException(
            "Certificate authority alias has an invalid chain: " + alias);
      }
    }
  }

  private void requireMatchingKey(CertificateAuthorityMaterial.AuthorityKeyMaterial keyMaterial)
      throws GeneralSecurityException {
    var signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(keyMaterial.privateKey());
    signer.update(KEY_MATCH_CHALLENGE);
    var signature = signer.sign();

    var verifier = Signature.getInstance("SHA256withECDSA");
    verifier.initVerify(keyMaterial.certificate().getPublicKey());
    verifier.update(KEY_MATCH_CHALLENGE);
    if (!verifier.verify(signature)) {
      throw new CertificateAuthorityStoreException(
          "Certificate authority contains a mismatched private key");
    }
  }

  private UUID installationId(X509Certificate root) throws CertificateParsingException {
    var names = Optional.ofNullable(root.getSubjectAlternativeNames()).orElse(List.of());
    requireEqual(
        names.size(), 1, "Certificate authority root has no exact SPIFFE trust-domain identity");
    var name = names.iterator().next();
    requireEqual(
        name.getFirst(), 6, "Certificate authority root has no exact SPIFFE trust-domain identity");
    if (!(name.get(1) instanceof String identity)) {
      throw new CertificateAuthorityStoreException(INVALID_SPIFFE_IDENTITY);
    }
    var host = URI.create(identity).getHost();
    if (host == null) {
      throw new CertificateAuthorityStoreException(INVALID_SPIFFE_IDENTITY);
    }
    var installationId = UUID.fromString(host);
    requireEqual(identity, "spiffe://" + installationId, INVALID_SPIFFE_IDENTITY);
    return installationId;
  }

  private void requireEqual(Object actual, Object expected, String message) {
    if (!Objects.deepEquals(actual, expected)) {
      throw new CertificateAuthorityStoreException(message);
    }
  }

  private void requireExactAliases(KeyStore keyStore) throws KeyStoreException {
    var actual = new HashSet<String>();
    var aliases = keyStore.aliases();
    while (aliases.hasMoreElements()) {
      actual.add(aliases.nextElement());
    }
    if (!actual.equals(REQUIRED_ALIASES)) {
      throw new CertificateAuthorityStoreException(
          "Certificate authority secret must contain only the required aliases");
    }
  }

  private void requireSecureParent() {
    var parent = secretPath.getParent();
    try {
      if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
        try {
          Files.createDirectory(
              parent, PosixFilePermissions.asFileAttribute(OWNER_DIRECTORY_PERMISSIONS));
        } catch (FileAlreadyExistsException _) {
          // A concurrent replica created it; the checks below still validate the winner.
        }
      }
      if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
        throw new CertificateAuthorityStoreException(
            "Certificate authority parent must be a real directory");
      }
      requireOwnerOnly(parent, "Certificate authority parent must be owner-only");
    } catch (CertificateAuthorityStoreException e) {
      throw e;
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      throw new CertificateAuthorityStoreException(
          "Failed to secure certificate authority parent directory", e);
    }
  }

  private void requireProtectedAncestors() {
    var ancestors = posixOwnerValidator.requireProtectedAncestors(secretPath.getParent());
    for (var ancestor : ancestors) {
      macOsAclValidator.requireNoAccessGrants(ancestor);
    }
  }

  private void requireDurableParentEntry() {
    var parent = secretPath.getParent();
    try {
      forceDirectory(Optional.ofNullable(parent.getParent()).orElse(parent));
    } catch (IOException e) {
      throw new CertificateAuthorityStoreException(
          "Failed to persist certificate authority parent directory", e);
    }
  }

  private void requireSecureRegularFile() {
    if (!Files.isRegularFile(secretPath, LinkOption.NOFOLLOW_LINKS)) {
      throw new CertificateAuthorityStoreException(
          "Certificate authority secret must be a regular file");
    }
    requireOwnerOnly(secretPath, "Certificate authority secret must be owner-only");
  }

  private void requireOwnerOnly(Path path, String message) {
    try {
      if (!Files.getFileStore(path).supportsFileAttributeView("posix")) {
        throw new CertificateAuthorityStoreException(
            "Certificate authority storage requires POSIX permissions");
      }
      posixOwnerValidator.requireCurrentOwner(path);
      var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
      if (permissions.stream().anyMatch(permission -> !permission.name().startsWith("OWNER_"))) {
        throw new CertificateAuthorityStoreException(message);
      }
      macOsAclValidator.requireAbsent(path);
    } catch (CertificateAuthorityStoreException e) {
      throw e;
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      throw new CertificateAuthorityStoreException("Failed to inspect secret permissions", e);
    }
  }

  private Path createTemporaryFile() throws IOException {
    var parent = secretPath.getParent();
    return Files.createTempFile(
        parent,
        ".streamarr-authority-",
        ".tmp",
        PosixFilePermissions.asFileAttribute(OWNER_FILE_PERMISSIONS));
  }

  private void forceDirectory(Path path) throws IOException {
    try (var directory = FileChannel.open(path, StandardOpenOption.READ)) {
      directory.force(true);
    }
  }

  private static char[] password() {
    return new char[0];
  }
}

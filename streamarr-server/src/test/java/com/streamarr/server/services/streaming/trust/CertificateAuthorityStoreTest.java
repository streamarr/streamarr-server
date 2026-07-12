package com.streamarr.server.services.streaming.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Certificate Authority Store Tests")
class CertificateAuthorityStoreTest {

  private static final Instant ISSUED_AT = Instant.parse("2026-07-12T12:00:00Z");
  private static final UUID INSTALLATION_ID =
      UUID.fromString("72ddb685-348a-4563-adf1-f7806df828cb");

  @TempDir Path directory;

  private Path secretPath;

  @BeforeEach
  void secureDirectory() throws Exception {
    secretPath = directory.resolve("authority.p12");
    if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  @DisplayName("Should reject storage path without a dedicated parent and final filename")
  void shouldRejectStoragePathWithoutDedicatedParentAndFinalFilename() {
    var root = Path.of("/");

    assertThatThrownBy(() -> new CertificateAuthorityStore(root))
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("dedicated parent and final filename");
  }

  @Test
  @DisplayName("Should reject missing authority material before creating storage")
  void shouldRejectMissingAuthorityMaterialBeforeCreatingStorage() throws Exception {
    var store = new CertificateAuthorityStore(secretPath);

    assertThatThrownBy(() -> store.createIfAbsent(null))
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("material is required");
    try (var files = Files.list(directory)) {
      assertThat(files).isEmpty();
    }
  }

  @Test
  @DisplayName("Should atomically round trip owner-only authority when file is created and loaded")
  void shouldAtomicallyRoundTripOwnerOnlyAuthorityWhenFileIsCreatedAndLoaded() throws Exception {
    var expected = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var store = new CertificateAuthorityStore(secretPath);

    var created = store.createIfAbsent(expected);
    var retained = store.createIfAbsent(expected);
    var loaded = store.load().orElseThrow();

    assertMaterialEquals(created, expected);
    assertMaterialEquals(retained, expected);
    assertMaterialEquals(loaded, expected);
    assertThat(created.toString()).doesNotContain("EC Private Key", "privateValue", "S:");
    assertThat(
            new CertificateAuthorityMaterial.AuthorityChainMaterial(
                new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                    expected.rootPrivateKey(), expected.rootCertificate()),
                new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                    expected.issuerPrivateKey(), expected.issuerCertificate()),
                new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                    expected.revocationSignerPrivateKey(), expected.revocationSignerCertificate())))
        .hasToString("AuthorityChainMaterial[privateKeys=REDACTED]");
    assertThat(
            new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                expected.rootPrivateKey(), expected.rootCertificate()))
        .hasToString("AuthorityKeyMaterial[privateKey=REDACTED]");
    if (Files.getFileStore(secretPath).supportsFileAttributeView("posix")) {
      assertThat(Files.getPosixFilePermissions(secretPath))
          .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }
    try (var files = Files.list(directory)) {
      assertThat(files.map(Path::getFileName).map(Path::toString).toList())
          .containsExactly("authority.p12");
    }
  }

  @Test
  @DisplayName("Should preserve the first complete authority when creation races")
  void shouldPreserveFirstCompleteAuthorityWhenCreationRaces() {
    var first = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var second =
        new BuiltInCertificateAuthority()
            .create(UUID.fromString("c81e09c3-15fc-4f72-bcd6-883f31f6fc96"), ISSUED_AT);
    var retained = createConcurrently(secretPath, 8, index -> index % 2 == 0 ? first : second);

    var winner = new CertificateAuthorityStore(secretPath).load().orElseThrow();

    assertThat(retained).hasSize(8);
    retained.forEach(material -> assertMaterialEquals(material, winner));
  }

  @Test
  @DisplayName("Should converge when dedicated parent creation races")
  void shouldConvergeWhenDedicatedParentCreationRaces() {
    var missingParent = directory.resolve("authority");
    var sharedSecret = missingParent.resolve("authority.p12");
    var material = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var retained = createConcurrently(sharedSecret, 32, _ -> material);

    var winner = new CertificateAuthorityStore(sharedSecret).load().orElseThrow();

    assertThat(retained).hasSize(32);
    retained.forEach(result -> assertMaterialEquals(result, winner));
  }

  @Test
  @DisplayName("Should reject authority file when path is a symbolic link")
  void shouldRejectAuthorityFileWhenPathIsSymbolicLink() throws Exception {
    var other = directory.resolve("other.p12");
    Files.write(other, new byte[] {1, 2, 3});
    Files.createSymbolicLink(secretPath, other.getFileName());
    var store = new CertificateAuthorityStore(secretPath);

    assertThatThrownBy(store::load)
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("regular file");
  }

  @Test
  @DisplayName("Should reject authority file when readable by another user")
  void shouldRejectAuthorityFileWhenReadableByAnotherUser() throws Exception {
    if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) {
      return;
    }
    var material = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var store = new CertificateAuthorityStore(secretPath);
    store.createIfAbsent(material);
    Files.setPosixFilePermissions(secretPath, PosixFilePermissions.fromString("rw-r--r--"));

    assertThatThrownBy(store::load)
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("owner-only");
  }

  @Test
  @DisplayName("Should reject authority when parent directory is accessible by another user")
  void shouldRejectAuthorityWhenParentDirectoryIsAccessibleByAnotherUser() throws Exception {
    if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) {
      return;
    }
    var material = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var store = new CertificateAuthorityStore(secretPath);
    store.createIfAbsent(material);
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));

    assertThatThrownBy(store::load)
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("parent must be owner-only");
  }

  @Test
  @DisplayName("Should reject authority parent entry exposed through writable ancestor")
  void shouldRejectAuthorityParentEntryExposedThroughWritableAncestor() throws Exception {
    if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) {
      return;
    }
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));
    var parent =
        Files.createDirectory(
            directory.resolve("authority"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    var storagePath = parent.resolve("authority.p12");

    try {
      assertThatThrownBy(() -> new CertificateAuthorityStore(storagePath))
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("protected ancestor");
    } finally {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  @DisplayName("Should accept authority parent entry protected by sticky writable ancestor")
  void shouldAcceptAuthorityParentEntryProtectedByStickyWritableAncestor() throws Exception {
    if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) {
      return;
    }
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));
    runCommand(List.of("/bin/chmod", "+t", directory.toString()));
    var parent =
        Files.createDirectory(
            directory.resolve("authority"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    var material = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);

    try {
      var retained =
          new CertificateAuthorityStore(parent.resolve("authority.p12")).createIfAbsent(material);

      assertMaterialEquals(retained, material);
    } finally {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  @DisplayName("Should reject load after protected ancestor becomes non-sticky writable")
  void shouldRejectLoadAfterProtectedAncestorBecomesNonStickyWritable() throws Exception {
    if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) {
      return;
    }
    var parent =
        Files.createDirectory(
            directory.resolve("authority"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    var store = new CertificateAuthorityStore(parent.resolve("authority.p12"));
    var material = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    store.createIfAbsent(material);
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));

    try {
      assertThatThrownBy(store::load)
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("protected ancestor");
    } finally {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  @DisplayName("Should resolve protected ancestor symbolic link before storing authority")
  void shouldResolveProtectedAncestorSymbolicLinkBeforeStoringAuthority() throws Exception {
    var actual =
        Files.createDirectory(
            directory.resolve("actual"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    var alias = directory.resolve("alias");
    Files.createSymbolicLink(alias, actual.getFileName());
    var material = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);

    new CertificateAuthorityStore(alias.resolve("authority").resolve("authority.p12"))
        .createIfAbsent(material);

    assertThat(actual.resolve("authority").resolve("authority.p12")).isRegularFile();
  }

  @Test
  @EnabledOnOs(OS.MAC)
  @DisplayName("Should reject authority when secure parent has a macOS extended ACL")
  void shouldRejectAuthorityWhenSecureParentHasMacOsExtendedAcl() throws Exception {
    var material = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var store = new CertificateAuthorityStore(secretPath);
    store.createIfAbsent(material);

    assertRejectsMacOsExtendedAcl(directory, "everyone allow search", store);
  }

  @Test
  @EnabledOnOs(OS.MAC)
  @DisplayName("Should reject authority when secret has a macOS extended ACL")
  void shouldRejectAuthorityWhenSecretHasMacOsExtendedAcl() throws Exception {
    var material = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var store = new CertificateAuthorityStore(secretPath);
    store.createIfAbsent(material);

    assertRejectsMacOsExtendedAcl(secretPath, "everyone allow read", store);
  }

  @Test
  @EnabledOnOs(OS.MAC)
  @DisplayName("Should accept authority below macOS ancestor with deny-only ACL")
  void shouldAcceptAuthorityBelowMacOsAncestorWithDenyOnlyAcl() throws Exception {
    var ancestor =
        Files.createDirectory(
            directory.resolve("ancestor"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    runCommand(List.of("/bin/chmod", "+a", "everyone deny delete", ancestor.toString()));
    var material = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);

    try {
      var retained =
          new CertificateAuthorityStore(ancestor.resolve("authority").resolve("authority.p12"))
              .createIfAbsent(material);

      assertMaterialEquals(retained, material);
    } finally {
      runCommand(List.of("/bin/chmod", "-N", ancestor.toString()));
    }
  }

  @Test
  @EnabledOnOs(OS.MAC)
  @DisplayName("Should reject authority below macOS ancestor with allow ACL")
  void shouldRejectAuthorityBelowMacOsAncestorWithAllowAcl() throws Exception {
    var ancestor =
        Files.createDirectory(
            directory.resolve("ancestor"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    runCommand(List.of("/bin/chmod", "+a", "everyone allow read", ancestor.toString()));
    var storagePath = ancestor.resolve("authority").resolve("authority.p12");

    try {
      assertThatThrownBy(() -> new CertificateAuthorityStore(storagePath))
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("must not grant access");
    } finally {
      runCommand(List.of("/bin/chmod", "-N", ancestor.toString()));
    }
  }

  @Test
  @DisplayName("Should reject storage when POSIX permissions cannot be enforced")
  void shouldRejectStorageWhenPosixPermissionsCannotBeEnforced() throws Exception {
    try (var fileSystem = Jimfs.newFileSystem(Configuration.windows())) {
      var parent = fileSystem.getPath("C:\\authority");
      Files.createDirectory(parent);
      var storagePath = parent.resolve("authority.p12");

      assertThatThrownBy(() -> new CertificateAuthorityStore(storagePath))
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("requires POSIX permissions");
    }
  }

  @Test
  @DisplayName("Should fail with storage exception when missing parent cannot enforce POSIX mode")
  void shouldFailWithStorageExceptionWhenMissingParentCannotEnforcePosixMode() throws Exception {
    try (var fileSystem = Jimfs.newFileSystem(Configuration.windows())) {
      var storagePath = fileSystem.getPath("C:\\authority").resolve("authority.p12");

      assertThatThrownBy(() -> new CertificateAuthorityStore(storagePath))
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("requires POSIX permissions");
    }
  }

  @Test
  @DisplayName("Should reject authority hierarchy owned by another operating-system user")
  void shouldRejectAuthorityHierarchyOwnedByAnotherOperatingSystemUser() throws Exception {
    var configuration =
        Configuration.unix().toBuilder()
            .setAttributeViews("basic", "owner", "posix", "unix")
            .build();
    try (var fileSystem = Jimfs.newFileSystem(configuration)) {
      var parent =
          Files.createDirectory(
              fileSystem.getPath("/authority"),
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      var secret =
          Files.createFile(
              parent.resolve("authority.p12"),
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      var foreignOwner =
          fileSystem.getUserPrincipalLookupService().lookupPrincipalByName("foreign-owner");
      Files.setOwner(parent, foreignOwner);
      Files.setOwner(secret, foreignOwner);

      assertThatThrownBy(() -> new CertificateAuthorityStore(secret))
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("owned by root or the current process user");
    }
  }

  @Test
  @DisplayName("Should create dedicated parent with owner-only permissions when missing")
  void shouldCreateDedicatedParentWithOwnerOnlyPermissionsWhenMissing() throws Exception {
    var parent = directory.resolve("authority");
    var nestedSecret = parent.resolve("authority.p12");
    var material = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);

    new CertificateAuthorityStore(nestedSecret).createIfAbsent(material);

    assertThat(nestedSecret).isRegularFile();
    if (Files.getFileStore(parent).supportsFileAttributeView("posix")) {
      assertThat(Files.getPosixFilePermissions(parent))
          .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  @DisplayName("Should report no authority without creating a missing dedicated parent on load")
  void shouldReportNoAuthorityWithoutCreatingMissingDedicatedParentOnLoad() {
    var parent = directory.resolve("authority");
    var store = new CertificateAuthorityStore(parent.resolve("authority.p12"));

    assertThat(store.load()).isEmpty();
    assertThat(parent).doesNotExist();
  }

  @Test
  @DisplayName("Should load authority when secure container ancestor is traverse-only")
  void shouldLoadAuthorityWhenSecureContainerAncestorIsTraverseOnly() throws Exception {
    if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) {
      return;
    }
    var ancestor = directory.resolve("ancestor");
    var parent = ancestor.resolve("authority");
    Files.createDirectory(
        ancestor,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    Files.createDirectory(
        parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    var path = parent.resolve("authority.p12");
    var expected = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var store = new CertificateAuthorityStore(path);
    store.createIfAbsent(expected);
    Files.setPosixFilePermissions(ancestor, PosixFilePermissions.fromString("--x------"));

    try {
      assertThat(Files.readAllBytes(path)).isNotEmpty();
      assertMaterialEquals(store.load().orElseThrow(), expected);
    } finally {
      Files.setPosixFilePermissions(ancestor, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  @DisplayName("Should fail closed when secure container entry cannot be made durable")
  void shouldFailClosedWhenSecureContainerEntryCannotBeMadeDurable() throws Exception {
    if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) {
      return;
    }
    var ancestor = directory.resolve("ancestor");
    var parent = ancestor.resolve("authority");
    Files.createDirectory(
        ancestor,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    Files.createDirectory(
        parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    var path = parent.resolve("authority.p12");
    var material = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var store = new CertificateAuthorityStore(path);
    Files.setPosixFilePermissions(ancestor, PosixFilePermissions.fromString("--x------"));

    try {
      assertThatThrownBy(() -> store.createIfAbsent(material))
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("persist certificate authority parent");
      assertThat(path).doesNotExist();
    } finally {
      Files.setPosixFilePermissions(ancestor, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  @DisplayName("Should fail closed when the dedicated parent's ancestor is missing")
  void shouldFailClosedWhenDedicatedParentAncestorIsMissing() {
    var nestedSecret = directory.resolve("missing").resolve("authority").resolve("authority.p12");

    assertThatThrownBy(() -> new CertificateAuthorityStore(nestedSecret))
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("protected ancestor does not exist");
  }

  @Test
  @DisplayName("Should reject dedicated parent when it is a symbolic link")
  void shouldRejectDedicatedParentWhenItIsSymbolicLink() throws Exception {
    var actualParent = directory.resolve("actual");
    Files.createDirectory(
        actualParent,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    var linkedParent = directory.resolve("linked");
    Files.createSymbolicLink(linkedParent, actualParent.getFileName());
    var material = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var store = new CertificateAuthorityStore(linkedParent.resolve("authority.p12"));

    assertThatThrownBy(() -> store.createIfAbsent(material))
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("real directory");
  }

  @Test
  @DisplayName("Should reject symbolic parent before probing a missing final secret")
  void shouldRejectSymbolicParentBeforeProbingMissingFinalSecret() throws Exception {
    var actualParent = directory.resolve("actual");
    Files.createDirectory(
        actualParent,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    var linkedParent = directory.resolve("linked");
    Files.createSymbolicLink(linkedParent, actualParent.getFileName());
    var store = new CertificateAuthorityStore(linkedParent.resolve("authority.p12"));

    assertThatThrownBy(store::load)
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("real directory");
  }

  @Test
  @DisplayName("Should reject authority container when unexpected entry exists")
  void shouldRejectAuthorityContainerWhenUnexpectedEntryExists() throws Exception {
    var material = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var store = new CertificateAuthorityStore(secretPath);
    store.createIfAbsent(material);
    var keyStore = loadKeyStore();
    keyStore.setCertificateEntry("unexpected", material.rootCertificate());
    writeKeyStore(keyStore);

    assertThatThrownBy(store::load)
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("required aliases");
  }

  @Test
  @DisplayName("Should reject authority alias when private key is missing")
  void shouldRejectAuthorityAliasWhenPrivateKeyIsMissing() throws Exception {
    var material = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var store = new CertificateAuthorityStore(secretPath);
    store.createIfAbsent(material);
    var keyStore = loadKeyStore();
    keyStore.deleteEntry("streamarr-root");
    keyStore.setCertificateEntry("streamarr-root", material.rootCertificate());
    writeKeyStore(keyStore);

    assertThatThrownBy(store::load)
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("private key");
  }

  @Test
  @DisplayName("Should reject issuer chain when truncated")
  void shouldRejectIssuerChainWhenTruncated() throws Exception {
    var material = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var foreign =
        new BuiltInCertificateAuthority()
            .create(UUID.fromString("d4e8de65-9bcc-4f23-81ce-1d9948eec11d"), ISSUED_AT);
    var store = new CertificateAuthorityStore(secretPath);
    store.createIfAbsent(material);
    rewriteAuthorityChains(material, foreign, ChainShape.TRUNCATED);

    assertThatThrownBy(store::load)
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("invalid chain");
  }

  @Test
  @DisplayName("Should reject authority chain when full-length chain ends at another root")
  void shouldRejectAuthorityChainWhenFullLengthChainEndsAtAnotherRoot() throws Exception {
    var material = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var foreign =
        new BuiltInCertificateAuthority()
            .create(UUID.fromString("76588b47-b8eb-4ba4-b328-b9f1849f657a"), ISSUED_AT);
    var store = new CertificateAuthorityStore(secretPath);
    store.createIfAbsent(material);
    rewriteAuthorityChains(material, foreign, ChainShape.FULL_LENGTH_WRONG_ROOT);

    assertThatThrownBy(store::load)
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("invalid chain");
  }

  @Test
  @DisplayName("Should reject private key when associated with another certificate")
  void shouldRejectPrivateKeyWhenAssociatedWithAnotherCertificate() throws Exception {
    var material = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var other =
        new BuiltInCertificateAuthority()
            .create(UUID.fromString("1cf8b73a-3980-4f04-b976-9a938aafbf99"), ISSUED_AT);
    var store = new CertificateAuthorityStore(secretPath);
    store.createIfAbsent(material);
    var keyStore = loadKeyStore();
    keyStore.setKeyEntry(
        "streamarr-root",
        other.rootPrivateKey(),
        new char[0],
        new Certificate[] {material.rootCertificate()});
    writeKeyStore(keyStore);

    assertThatThrownBy(store::load)
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("mismatched private key");
  }

  @Test
  @DisplayName("Should leave no secret when authority persistence fails")
  void shouldLeaveNoSecretWhenAuthorityPersistenceFails() throws Exception {
    var valid = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var invalid =
        new CertificateAuthorityMaterial(
            INSTALLATION_ID,
            new CertificateAuthorityMaterial.AuthorityChainMaterial(
                new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                    null, valid.rootCertificate()),
                new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                    valid.issuerPrivateKey(), valid.issuerCertificate()),
                new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                    valid.revocationSignerPrivateKey(), valid.revocationSignerCertificate())));
    var store = new CertificateAuthorityStore(secretPath);

    assertThatThrownBy(() -> store.createIfAbsent(invalid))
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("persist certificate authority secret");
    assertThat(secretPath).doesNotExist();
    try (var files = Files.list(directory)) {
      assertThat(files).isEmpty();
    }
  }

  @Test
  @DisplayName("Should leave no secret when secure parent is not writable")
  void shouldLeaveNoSecretWhenSecureParentIsNotWritable() throws Exception {
    if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) {
      return;
    }
    var material = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var store = new CertificateAuthorityStore(secretPath);
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));

    try {
      assertThatThrownBy(() -> store.createIfAbsent(material))
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("persist certificate authority secret");
      assertThat(secretPath).doesNotExist();
    } finally {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  @DisplayName("Should reject authority material when labeled for another installation")
  void shouldRejectAuthorityMaterialWhenLabeledForAnotherInstallation() throws Exception {
    var valid = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var mislabeled =
        new CertificateAuthorityMaterial(
            UUID.fromString("d4e8de65-9bcc-4f23-81ce-1d9948eec11d"),
            new CertificateAuthorityMaterial.AuthorityChainMaterial(
                new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                    valid.rootPrivateKey(), valid.rootCertificate()),
                new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                    valid.issuerPrivateKey(), valid.issuerCertificate()),
                new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                    valid.revocationSignerPrivateKey(), valid.revocationSignerCertificate())));
    var store = new CertificateAuthorityStore(secretPath);

    assertThatThrownBy(() -> store.createIfAbsent(mislabeled))
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("installation identity");
    assertThat(secretPath).doesNotExist();
    try (var files = Files.list(directory)) {
      assertThat(files).isEmpty();
    }
  }

  @Test
  @DisplayName("Should reject authority material when root trust-domain spelling is not canonical")
  void shouldRejectAuthorityMaterialWhenRootTrustDomainSpellingIsNotCanonical() throws Exception {
    var valid = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var malformed = withRootTrustDomain(valid, "SPIFFE://" + INSTALLATION_ID);
    var store = new CertificateAuthorityStore(secretPath);

    assertThatThrownBy(() -> store.createIfAbsent(malformed))
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("invalid SPIFFE trust-domain identity");
    assertThat(secretPath).doesNotExist();
  }

  @Test
  @DisplayName("Should reject authority material when root trust domain has no host")
  void shouldRejectAuthorityMaterialWhenRootTrustDomainHasNoHost() throws Exception {
    var valid = new BuiltInCertificateAuthority().create(INSTALLATION_ID, ISSUED_AT);
    var malformed = withRootTrustDomain(valid, "spiffe:///" + INSTALLATION_ID);
    var store = new CertificateAuthorityStore(secretPath);

    assertThatThrownBy(() -> store.createIfAbsent(malformed))
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("invalid SPIFFE trust-domain identity");
    assertThat(secretPath).doesNotExist();
  }

  @Test
  @DisplayName("Should reject authority without regeneration when stored material is corrupted")
  void shouldRejectAuthorityWithoutRegenerationWhenStoredMaterialIsCorrupted() throws Exception {
    Files.write(secretPath, new byte[] {1, 2, 3});
    if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
      Files.setPosixFilePermissions(secretPath, PosixFilePermissions.fromString("rw-------"));
    }
    var store = new CertificateAuthorityStore(secretPath);

    assertThatThrownBy(store::load)
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("read certificate authority");
    assertThat(Files.readAllBytes(secretPath)).containsExactly(1, 2, 3);
  }

  private static CopyOnWriteArrayList<CertificateAuthorityMaterial> createConcurrently(
      Path path, int count, IntFunction<CertificateAuthorityMaterial> candidateForIndex) {
    var start = new CountDownLatch(1);
    var retained = new CopyOnWriteArrayList<CertificateAuthorityMaterial>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var index = 0; index < count; index++) {
        var candidate = candidateForIndex.apply(index);
        executor.submit(
            () -> {
              start.await();
              retained.add(new CertificateAuthorityStore(path).createIfAbsent(candidate));
              return null;
            });
      }
      start.countDown();
    }
    return retained;
  }

  private static void assertRejectsMacOsExtendedAcl(
      Path path, String entry, CertificateAuthorityStore store) throws Exception {
    var modeBeforeAcl = Files.getPosixFilePermissions(path);
    runCommand(List.of("/bin/chmod", "+a", entry, path.toString()));
    try {
      assertThat(Files.getPosixFilePermissions(path)).isEqualTo(modeBeforeAcl);
      assertThatThrownBy(store::load)
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("extended ACL");
    } finally {
      runCommand(List.of("/bin/chmod", "-N", path.toString()));
    }
  }

  private static void runCommand(List<String> command) throws Exception {
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output;
    try (var input = process.getInputStream()) {
      output = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    assertThat(process.waitFor()).as(output).isZero();
  }

  private static CertificateAuthorityMaterial withRootTrustDomain(
      CertificateAuthorityMaterial original, String trustDomain) throws Exception {
    var root = original.rootCertificate();
    var subject = new X500Name(root.getSubjectX500Principal().getName());
    var builder =
        new JcaX509v3CertificateBuilder(
            subject,
            root.getSerialNumber(),
            root.getNotBefore(),
            root.getNotAfter(),
            subject,
            root.getPublicKey());
    var extensions = new JcaX509ExtensionUtils();
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(1));
    builder.addExtension(
        Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
    builder.addExtension(
        Extension.subjectKeyIdentifier,
        false,
        extensions.createSubjectKeyIdentifier(root.getPublicKey()));
    builder.addExtension(
        Extension.authorityKeyIdentifier,
        false,
        extensions.createAuthorityKeyIdentifier(root.getPublicKey()));
    builder.addExtension(
        Extension.subjectAlternativeName,
        false,
        new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, trustDomain)));
    var signer = new JcaContentSignerBuilder("SHA256withECDSA").build(original.rootPrivateKey());
    var malformedRoot = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    return new CertificateAuthorityMaterial(
        original.installationId(),
        new CertificateAuthorityMaterial.AuthorityChainMaterial(
            new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                original.rootPrivateKey(), malformedRoot),
            new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                original.issuerPrivateKey(), original.issuerCertificate()),
            new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                original.revocationSignerPrivateKey(), original.revocationSignerCertificate())));
  }

  private static void assertMaterialEquals(
      CertificateAuthorityMaterial actual, CertificateAuthorityMaterial expected) {
    assertThat(actual.installationId()).isEqualTo(expected.installationId());
    assertThat(actual.rootPrivateKey().getEncoded())
        .isEqualTo(expected.rootPrivateKey().getEncoded());
    assertThat(encoded(actual.rootCertificate())).isEqualTo(encoded(expected.rootCertificate()));
    assertThat(actual.issuerPrivateKey().getEncoded())
        .isEqualTo(expected.issuerPrivateKey().getEncoded());
    assertThat(encoded(actual.issuerCertificate()))
        .isEqualTo(encoded(expected.issuerCertificate()));
    assertThat(actual.revocationSignerPrivateKey().getEncoded())
        .isEqualTo(expected.revocationSignerPrivateKey().getEncoded());
    assertThat(encoded(actual.revocationSignerCertificate()))
        .isEqualTo(encoded(expected.revocationSignerCertificate()));
  }

  private KeyStore loadKeyStore() throws Exception {
    var keyStore = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(secretPath)) {
      keyStore.load(input, new char[0]);
    }
    return keyStore;
  }

  private void rewriteAuthorityChains(
      CertificateAuthorityMaterial material,
      CertificateAuthorityMaterial foreignRoot,
      ChainShape shape)
      throws Exception {
    var keyStore = loadKeyStore();
    keyStore.deleteEntry("streamarr-root");
    keyStore.deleteEntry("streamarr-issuer");
    keyStore.deleteEntry("streamarr-revocation-signer");
    keyStore.setKeyEntry(
        "streamarr-root",
        foreignRoot.rootPrivateKey(),
        new char[0],
        new Certificate[] {foreignRoot.rootCertificate()});
    var issuerChain =
        switch (shape) {
          case TRUNCATED -> new Certificate[] {material.issuerCertificate()};
          case FULL_LENGTH_WRONG_ROOT ->
              new Certificate[] {material.issuerCertificate(), material.rootCertificate()};
        };
    var signerChain =
        switch (shape) {
          case TRUNCATED -> new Certificate[] {material.revocationSignerCertificate()};
          case FULL_LENGTH_WRONG_ROOT ->
              new Certificate[] {
                material.revocationSignerCertificate(),
                material.issuerCertificate(),
                material.rootCertificate()
              };
        };
    keyStore.setKeyEntry("streamarr-issuer", material.issuerPrivateKey(), new char[0], issuerChain);
    keyStore.setKeyEntry(
        "streamarr-revocation-signer",
        material.revocationSignerPrivateKey(),
        new char[0],
        signerChain);
    writeKeyStore(keyStore);
  }

  private void writeKeyStore(KeyStore keyStore) throws Exception {
    try (var output = Files.newOutputStream(secretPath)) {
      keyStore.store(output, new char[0]);
    }
  }

  private static byte[] encoded(java.security.cert.X509Certificate certificate) {
    try {
      return certificate.getEncoded();
    } catch (java.security.cert.CertificateEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private enum ChainShape {
    TRUNCATED,
    FULL_LENGTH_WRONG_ROOT
  }
}

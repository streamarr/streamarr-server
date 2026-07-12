package com.streamarr.server.services.streaming.trust;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("POSIX Owner Validator Tests")
class PosixOwnerValidatorTest {

  @Test
  @DisplayName("Should reject path owned by another operating-system user")
  void shouldRejectPathOwnedByAnotherOperatingSystemUser() throws Exception {
    var configuration =
        Configuration.unix().toBuilder()
            .setAttributeViews("basic", "owner", "posix", "unix")
            .build();
    try (var fileSystem = Jimfs.newFileSystem(configuration)) {
      var path = Files.createFile(fileSystem.getPath("/secret"));
      var validator = new PosixOwnerValidator(2L);

      assertThatThrownBy(() -> validator.requireCurrentOwner(path))
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("owned by the current process user");
    }
  }

  @Test
  @DisplayName("Should accept path owned by the current operating-system user")
  void shouldAcceptPathOwnedByCurrentOperatingSystemUser() throws Exception {
    var configuration =
        Configuration.unix().toBuilder()
            .setAttributeViews("basic", "owner", "posix", "unix")
            .build();
    try (var fileSystem = Jimfs.newFileSystem(configuration)) {
      var path = Files.createFile(fileSystem.getPath("/secret"));
      var validator = new PosixOwnerValidator(1L);

      assertThatCode(() -> validator.requireCurrentOwner(path)).doesNotThrowAnyException();
    }
  }

  @Test
  @DisplayName("Should fail closed when numeric POSIX ownership cannot be inspected")
  void shouldFailClosedWhenNumericPosixOwnershipCannotBeInspected() throws Exception {
    var configuration =
        Configuration.unix().toBuilder().setAttributeViews("basic", "owner", "posix").build();
    try (var fileSystem = Jimfs.newFileSystem(configuration)) {
      var path = Files.createFile(fileSystem.getPath("/secret"));
      var validator = new PosixOwnerValidator(1L);

      assertThatThrownBy(() -> validator.requireCurrentOwner(path))
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("Failed to inspect certificate authority path ownership");
    }
  }

  @Test
  @DisplayName("Should preserve storage failure when current process user cannot be determined")
  void shouldPreserveStorageFailureWhenCurrentProcessUserCannotBeDetermined() throws Exception {
    var configuration =
        Configuration.unix().toBuilder()
            .setAttributeViews("basic", "owner", "posix", "unix")
            .build();
    try (var fileSystem = Jimfs.newFileSystem(configuration)) {
      var path = Files.createFile(fileSystem.getPath("/secret"));
      var failure =
          new CertificateAuthorityStoreException("Failed to determine the current process user");
      var validator =
          new PosixOwnerValidator(
              () -> {
                throw failure;
              });

      assertThatThrownBy(() -> validator.requireCurrentOwner(path)).isSameAs(failure);
    }
  }

  @Test
  @DisplayName("Should reject protected ancestor when shared writable mode has no sticky bit")
  void shouldRejectProtectedAncestorWhenSharedWritableModeHasNoStickyBit() throws Exception {
    var configuration =
        Configuration.unix().toBuilder()
            .setAttributeViews("basic", "owner", "posix", "unix")
            .build();
    try (var fileSystem = Jimfs.newFileSystem(configuration)) {
      var ancestor = Files.createDirectory(fileSystem.getPath("/unsafe"));
      Files.setPosixFilePermissions(ancestor, PosixFilePermissions.fromString("rwxrwxrwx"));
      var entry = Files.createDirectory(ancestor.resolve("authority"));
      var validator = new PosixOwnerValidator(1L);

      assertThatThrownBy(() -> validator.requireProtectedAncestors(entry))
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("sticky bit");
    }
  }

  @Test
  @DisplayName("Should accept protected ancestors owned by root or current process user")
  void shouldAcceptProtectedAncestorsOwnedByRootOrCurrentProcessUser() throws Exception {
    var configuration =
        Configuration.unix().toBuilder()
            .setAttributeViews("basic", "owner", "posix", "unix")
            .build();
    try (var fileSystem = Jimfs.newFileSystem(configuration)) {
      var ancestor = Files.createDirectory(fileSystem.getPath("/safe"));
      Files.setPosixFilePermissions(ancestor, PosixFilePermissions.fromString("rwxr-xr-x"));
      var entry = Files.createDirectory(ancestor.resolve("authority"));
      var validator = new PosixOwnerValidator(1L);

      assertThatCode(() -> validator.requireProtectedAncestors(entry)).doesNotThrowAnyException();
    }
  }

  @Test
  @DisplayName("Should reject symbolic link used as a protected directory")
  void shouldRejectSymbolicLinkUsedAsProtectedDirectory() throws Exception {
    var configuration =
        Configuration.unix().toBuilder()
            .setAttributeViews("basic", "owner", "posix", "unix")
            .build();
    try (var fileSystem = Jimfs.newFileSystem(configuration)) {
      var actual = Files.createDirectory(fileSystem.getPath("/actual"));
      var alias = fileSystem.getPath("/alias");
      Files.createSymbolicLink(alias, actual);
      var validator = new PosixOwnerValidator(1L);

      assertThatThrownBy(() -> validator.requireProtectedDirectory(alias))
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("real directory");
    }
  }

  @Test
  @DisplayName("Should reject protected entry owned by an untrusted operating-system user")
  void shouldRejectProtectedEntryOwnedByUntrustedOperatingSystemUser() throws Exception {
    var configuration =
        Configuration.unix().toBuilder()
            .setAttributeViews("basic", "owner", "posix", "unix")
            .build();
    try (var fileSystem = Jimfs.newFileSystem(configuration)) {
      var entry = Files.createDirectory(fileSystem.getPath("/entry"));
      var validator = new PosixOwnerValidator(2L);

      assertThatThrownBy(() -> validator.requireTrustedEntry(entry))
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("protected entry must be owned");
    }
  }

  @Test
  @DisplayName("Should fail closed when protected entry numeric ownership cannot be inspected")
  void shouldFailClosedWhenProtectedEntryNumericOwnershipCannotBeInspected() throws Exception {
    var configuration =
        Configuration.unix().toBuilder().setAttributeViews("basic", "owner", "posix").build();
    try (var fileSystem = Jimfs.newFileSystem(configuration)) {
      var entry = Files.createDirectory(fileSystem.getPath("/entry"));
      var validator = new PosixOwnerValidator(1L);

      assertThatThrownBy(() -> validator.requireTrustedEntry(entry))
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("Failed to inspect certificate authority protected entry");
    }
  }
}

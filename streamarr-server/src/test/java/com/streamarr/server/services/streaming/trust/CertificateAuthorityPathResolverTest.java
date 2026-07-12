package com.streamarr.server.services.streaming.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Certificate Authority Path Resolver Tests")
class CertificateAuthorityPathResolverTest {

  @TempDir Path directory;

  @Test
  @DisplayName("Should reject missing secret path")
  void shouldRejectMissingSecretPath() {
    assertThatThrownBy(() -> resolver().resolve(null))
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("path is required");
  }

  @Test
  @DisplayName("Should reject secret path directly below filesystem root")
  void shouldRejectSecretPathDirectlyBelowFilesystemRoot() throws Exception {
    try (var fileSystem = newFileSystem()) {
      assertThatThrownBy(() -> resolver().resolve(fileSystem.getPath("/authority.p12")))
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("dedicated parent and final filename");
    }
  }

  @Test
  @DisplayName("Should reject parent traversal in requested secret path")
  void shouldRejectParentTraversalInRequestedSecretPath() throws Exception {
    try (var fileSystem = newFileSystem()) {
      Files.createDirectory(fileSystem.getPath("/actual"));
      var requested = fileSystem.getPath("/actual/../authority/authority.p12");

      assertThatThrownBy(() -> resolver().resolve(requested))
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("parent traversal");
    }
  }

  @Test
  @DisplayName("Should reject parent traversal in protected ancestor symbolic-link target")
  void shouldRejectParentTraversalInProtectedAncestorSymbolicLinkTarget() throws Exception {
    try (var fileSystem = newFileSystem()) {
      var base = Files.createDirectory(fileSystem.getPath("/base"));
      var real = Files.createDirectory(base.resolve("real"));
      var target = Files.createDirectory(fileSystem.getPath("/target"));
      Files.createDirectory(target.resolve("child"));
      Files.createSymbolicLink(real.resolve("link"), target.resolve("child"));
      Files.createSymbolicLink(base.resolve("alias"), fileSystem.getPath("real/link/.."));

      var requested = base.resolve("alias/authority/authority.p12");

      assertThatThrownBy(() -> resolver().resolve(requested))
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("parent traversal");
    }
  }

  @Test
  @DisplayName("Should resolve absolute protected ancestor symbolic link")
  void shouldResolveAbsoluteProtectedAncestorSymbolicLink() throws Exception {
    try (var fileSystem = newFileSystem()) {
      var actual =
          Files.createDirectory(
              fileSystem.getPath("/actual"),
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
      Files.createSymbolicLink(fileSystem.getPath("/alias"), actual);

      var resolved = resolver().resolve(fileSystem.getPath("/alias/authority/authority.p12"));

      assertThat(resolved).isEqualTo(actual.resolve("authority/authority.p12"));
    }
  }

  @Test
  @DisplayName("Should reject protected ancestor symbolic-link cycle")
  void shouldRejectProtectedAncestorSymbolicLinkCycle() throws Exception {
    try (var fileSystem = newFileSystem()) {
      var loop = fileSystem.getPath("/loop");
      Files.createSymbolicLink(loop, loop);

      assertThatThrownBy(() -> resolver().resolve(loop.resolve("authority/authority.p12")))
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("symbolic-link depth or cycle");
    }
  }

  @Test
  @DisplayName("Should reject excessive protected ancestor symbolic-link depth")
  void shouldRejectExcessiveProtectedAncestorSymbolicLinkDepth() throws Exception {
    try (var fileSystem = newFileSystem()) {
      var target = Files.createDirectory(fileSystem.getPath("/target"));
      for (var index = 40; index >= 0; index--) {
        var link = fileSystem.getPath("/link-" + index);
        var next = index == 40 ? target : fileSystem.getPath("/link-" + (index + 1));
        Files.createSymbolicLink(link, next);
      }

      assertThatThrownBy(
              () -> resolver().resolve(fileSystem.getPath("/link-0/authority/authority.p12")))
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("symbolic-link depth or cycle");
    }
  }

  @Test
  @DisplayName("Should resolve trusted entry through sticky protected ancestor")
  void shouldResolveTrustedEntryThroughStickyProtectedAncestor() throws Exception {
    var sticky = Files.createDirectory(directory.resolve("sticky"));
    var chmod = new ProcessBuilder("/bin/chmod", "1777", sticky.toString()).start();
    assertThat(chmod.waitFor()).isZero();
    var trusted =
        Files.createDirectory(
            sticky.resolve("trusted"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    var resolver =
        new CertificateAuthorityPathResolver(
            new PosixOwnerValidator(), new MacOsExtendedAclValidator(false, _ -> false));

    try {
      var resolved = resolver.resolve(trusted.resolve("authority/authority.p12"));

      assertThat(resolved).isEqualTo(trusted.toRealPath().resolve("authority/authority.p12"));
    } finally {
      Files.setPosixFilePermissions(sticky, PosixFilePermissions.fromString("rwx------"));
    }
  }

  private static CertificateAuthorityPathResolver resolver() {
    return new CertificateAuthorityPathResolver(
        new PosixOwnerValidator(1L), new MacOsExtendedAclValidator(false, _ -> false));
  }

  private static java.nio.file.FileSystem newFileSystem() {
    var configuration =
        Configuration.unix().toBuilder()
            .setAttributeViews("basic", "owner", "posix", "unix")
            .build();
    return Jimfs.newFileSystem(configuration);
  }
}

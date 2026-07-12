package com.streamarr.server.services.streaming.trust;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("macOS Extended ACL Validator Tests")
class MacOsExtendedAclValidatorTest {

  private static final Path SECRET_PATH = Path.of("/authority/authority.p12");

  @Test
  @DisplayName("Should avoid ACL inspection when operating system is not macOS")
  void shouldAvoidAclInspectionWhenOperatingSystemIsNotMacOs() {
    var validator =
        new MacOsExtendedAclValidator(
            false,
            _ -> {
              throw new AssertionError("macOS ACL inspection must remain lazy");
            });

    assertThatCode(() -> validator.requireAbsent(SECRET_PATH)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should accept authority path when macOS reports no extended ACL")
  void shouldAcceptAuthorityPathWhenMacOsReportsNoExtendedAcl() {
    var validator = new MacOsExtendedAclValidator(true, _ -> false);

    assertThatCode(() -> validator.requireAbsent(SECRET_PATH)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should reject authority path when macOS reports an extended ACL")
  void shouldRejectAuthorityPathWhenMacOsReportsExtendedAcl() {
    var validator = new MacOsExtendedAclValidator(true, _ -> true);

    assertThatThrownBy(() -> validator.requireAbsent(SECRET_PATH))
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("must not have an extended ACL");
  }

  @Test
  @DisplayName("Should preserve a failed macOS ACL inspection")
  void shouldPreserveFailedMacOsAclInspection() {
    var failure = new CertificateAuthorityStoreException("ACL command failed");
    var validator =
        new MacOsExtendedAclValidator(
            true,
            _ -> {
              throw failure;
            });

    assertThatThrownBy(() -> validator.requireAbsent(SECRET_PATH)).isSameAs(failure);
  }

  @Test
  @DisplayName("Should accept ancestor when macOS ACL entries only deny access")
  void shouldAcceptAncestorWhenMacOsAclEntriesOnlyDenyAccess() {
    var validator = new MacOsExtendedAclValidator(true, _ -> true, _ -> false);

    assertThatCode(() -> validator.requireNoAccessGrants(SECRET_PATH)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should reject ancestor when a macOS ACL entry grants access")
  void shouldRejectAncestorWhenMacOsAclEntryGrantsAccess() {
    var validator = new MacOsExtendedAclValidator(true, _ -> true, _ -> true);

    assertThatThrownBy(() -> validator.requireNoAccessGrants(SECRET_PATH))
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("must not grant access");
  }

  @Test
  @DisplayName("Should avoid ancestor ACL inspection when operating system is not macOS")
  void shouldAvoidAncestorAclInspectionWhenOperatingSystemIsNotMacOs() {
    var validator =
        new MacOsExtendedAclValidator(
            false,
            _ -> false,
            _ -> {
              throw new AssertionError("macOS ACL inspection must remain lazy");
            });

    assertThatCode(() -> validator.requireNoAccessGrants(SECRET_PATH)).doesNotThrowAnyException();
  }
}

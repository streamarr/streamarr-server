package com.streamarr.server.domain.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Credential Attempt Target Tests")
class CredentialAttemptTargetTest {

  private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID PROFILE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
  private static final UUID CREDENTIAL_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
  private static final String IP_ADDRESS = "192.0.2.30";

  @Test
  @DisplayName("Should require the Profile when the kind is a Profile PIN")
  void shouldRequireProfileWhenKindIsProfilePin() {
    var target =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.PROFILE_PIN)
            .accountId(ACCOUNT_ID)
            .ipAddress(IP_ADDRESS);
    assertThatThrownBy(target::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("PROFILE_PIN target requires profileId");
  }

  @Test
  @DisplayName("Should require the Account when the kind is a Profile PIN")
  void shouldRequireAccountWhenKindIsProfilePin() {
    var target =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.PROFILE_PIN)
            .profileId(PROFILE_ID)
            .ipAddress(IP_ADDRESS);
    assertThatThrownBy(target::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("PROFILE_PIN target requires accountId");
  }

  @Test
  @DisplayName("Should require the approver's Account when the kind is a device pairing code")
  void shouldRequireApproverAccountWhenKindIsDevicePairingCode() {
    var target =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.DEVICE_PAIRING_CODE)
            .credentialId(CREDENTIAL_ID)
            .ipAddress(IP_ADDRESS);
    assertThatThrownBy(target::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("DEVICE_PAIRING_CODE target requires accountId");
  }

  @Test
  @DisplayName("Should require the Account when the kind is an Account password verification")
  void shouldRequireAccountWhenKindIsAccountPasswordVerification() {
    var target =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.ACCOUNT_PASSWORD_VERIFICATION)
            .ipAddress(IP_ADDRESS);
    assertThatThrownBy(target::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ACCOUNT_PASSWORD_VERIFICATION target requires accountId");
  }

  @ParameterizedTest
  @EnumSource(
      value = CredentialKind.class,
      names = {"ACCOUNT_INVITATION_CODE", "PASSWORD_RESET_CODE", "PROFILE_MANAGER_INVITATION_CODE"})
  @DisplayName("Should reject an Account identifier when the kind is an opaque code")
  void shouldRejectAccountIdentifierWhenKindIsOpaqueCode(CredentialKind kind) {
    var target =
        CredentialAttemptTarget.builder()
            .kind(kind)
            .accountId(ACCOUNT_ID)
            .credentialId(CREDENTIAL_ID)
            .ipAddress(IP_ADDRESS);
    assertThatThrownBy(target::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(kind + " target must not carry accountId");
  }

  @Test
  @DisplayName("Should reject a Profile identifier when the kind is a login")
  void shouldRejectProfileIdentifierWhenKindIsLogin() {
    var target =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.ACCOUNT_LOGIN)
            .accountId(ACCOUNT_ID)
            .profileId(PROFILE_ID)
            .ipAddress(IP_ADDRESS);
    assertThatThrownBy(target::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ACCOUNT_LOGIN target must not carry profileId");
  }

  @Test
  @DisplayName("Should accept a login target when the Account is unresolved")
  void shouldAcceptLoginTargetWhenAccountIsUnresolved() {
    var target =
        CredentialAttemptTarget.builder().kind(CredentialKind.ACCOUNT_LOGIN).ipAddress(IP_ADDRESS);

    assertThatCode(target::build).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should accept an opaque code target when the credential is unresolved")
  void shouldAcceptOpaqueCodeTargetWhenCredentialIsUnresolved() {
    var target =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.PASSWORD_RESET_CODE)
            .ipAddress(IP_ADDRESS);

    assertThatCode(target::build).doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("resolvedShapes")
  @DisplayName("Should accept a resolved target when its identifiers match the kind")
  void shouldAcceptResolvedTargetWhenIdentifiersMatchKind(
      CredentialKind kind, CredentialAttemptTarget.CredentialAttemptTargetBuilder target) {
    assertThatCode(target::build).doesNotThrowAnyException();
  }

  private static Stream<Arguments> resolvedShapes() {
    return Stream.of(
        Arguments.of(
            CredentialKind.ACCOUNT_LOGIN,
            CredentialAttemptTarget.builder()
                .kind(CredentialKind.ACCOUNT_LOGIN)
                .accountId(ACCOUNT_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.ACCOUNT_PASSWORD_VERIFICATION,
            CredentialAttemptTarget.builder()
                .kind(CredentialKind.ACCOUNT_PASSWORD_VERIFICATION)
                .accountId(ACCOUNT_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.PROFILE_PIN,
            CredentialAttemptTarget.builder()
                .kind(CredentialKind.PROFILE_PIN)
                .accountId(ACCOUNT_ID)
                .profileId(PROFILE_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.ACCOUNT_INVITATION_CODE,
            CredentialAttemptTarget.builder()
                .kind(CredentialKind.ACCOUNT_INVITATION_CODE)
                .credentialId(CREDENTIAL_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.PASSWORD_RESET_CODE,
            CredentialAttemptTarget.builder()
                .kind(CredentialKind.PASSWORD_RESET_CODE)
                .credentialId(CREDENTIAL_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.PROFILE_MANAGER_INVITATION_CODE,
            CredentialAttemptTarget.builder()
                .kind(CredentialKind.PROFILE_MANAGER_INVITATION_CODE)
                .credentialId(CREDENTIAL_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.DEVICE_PAIRING_CODE,
            CredentialAttemptTarget.builder()
                .kind(CredentialKind.DEVICE_PAIRING_CODE)
                .accountId(ACCOUNT_ID)
                .ipAddress(IP_ADDRESS)));
  }

  @ParameterizedTest(name = "{0} must not carry {1}")
  @MethodSource("forbiddenIdentifiers")
  @DisplayName("Should reject an identifier when the kind never resolves by it")
  void shouldRejectIdentifierWhenKindNeverResolvesByIt(
      CredentialKind kind,
      String identifier,
      CredentialAttemptTarget.CredentialAttemptTargetBuilder target) {
    assertThatThrownBy(target::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(kind + " target must not carry " + identifier);
  }

  private static Stream<Arguments> forbiddenIdentifiers() {
    return Stream.of(
        Arguments.of(
            CredentialKind.ACCOUNT_LOGIN,
            "credentialId",
            CredentialAttemptTarget.builder()
                .kind(CredentialKind.ACCOUNT_LOGIN)
                .accountId(ACCOUNT_ID)
                .credentialId(CREDENTIAL_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.ACCOUNT_PASSWORD_VERIFICATION,
            "profileId",
            CredentialAttemptTarget.builder()
                .kind(CredentialKind.ACCOUNT_PASSWORD_VERIFICATION)
                .accountId(ACCOUNT_ID)
                .profileId(PROFILE_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.ACCOUNT_PASSWORD_VERIFICATION,
            "credentialId",
            CredentialAttemptTarget.builder()
                .kind(CredentialKind.ACCOUNT_PASSWORD_VERIFICATION)
                .accountId(ACCOUNT_ID)
                .credentialId(CREDENTIAL_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.PROFILE_PIN,
            "credentialId",
            CredentialAttemptTarget.builder()
                .kind(CredentialKind.PROFILE_PIN)
                .accountId(ACCOUNT_ID)
                .profileId(PROFILE_ID)
                .credentialId(CREDENTIAL_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.DEVICE_PAIRING_CODE,
            "profileId",
            CredentialAttemptTarget.builder()
                .kind(CredentialKind.DEVICE_PAIRING_CODE)
                .accountId(ACCOUNT_ID)
                .profileId(PROFILE_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.DEVICE_PAIRING_CODE,
            "credentialId",
            CredentialAttemptTarget.builder()
                .kind(CredentialKind.DEVICE_PAIRING_CODE)
                .accountId(ACCOUNT_ID)
                .credentialId(CREDENTIAL_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.PASSWORD_RESET_CODE,
            "profileId",
            CredentialAttemptTarget.builder()
                .kind(CredentialKind.PASSWORD_RESET_CODE)
                .profileId(PROFILE_ID)
                .credentialId(CREDENTIAL_ID)
                .ipAddress(IP_ADDRESS)));
  }
}

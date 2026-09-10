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
@DisplayName("Credential Attempt Metadata Tests")
class CredentialAttemptMetadataTest {

  private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID PROFILE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
  private static final UUID CREDENTIAL_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
  private static final String IP_ADDRESS = "192.0.2.30";

  @Test
  @DisplayName("Should require the Profile when the kind is a Profile PIN")
  void shouldRequireProfileWhenKindIsProfilePin() {
    var metadata =
        CredentialAttemptMetadata.builder()
            .kind(CredentialKind.PROFILE_PIN)
            .accountId(ACCOUNT_ID)
            .ipAddress(IP_ADDRESS);
    assertThatThrownBy(metadata::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("PROFILE_PIN target requires profileId");
  }

  @Test
  @DisplayName("Should require the Account when the kind is a Profile PIN")
  void shouldRequireAccountWhenKindIsProfilePin() {
    var metadata =
        CredentialAttemptMetadata.builder()
            .kind(CredentialKind.PROFILE_PIN)
            .profileId(PROFILE_ID)
            .ipAddress(IP_ADDRESS);
    assertThatThrownBy(metadata::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("PROFILE_PIN target requires accountId");
  }

  @Test
  @DisplayName("Should require the approver's Account when the kind is a device pairing code")
  void shouldRequireApproverAccountWhenKindIsDevicePairingCode() {
    var metadata =
        CredentialAttemptMetadata.builder()
            .kind(CredentialKind.DEVICE_PAIRING_CODE)
            .credentialId(CREDENTIAL_ID)
            .ipAddress(IP_ADDRESS);
    assertThatThrownBy(metadata::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("DEVICE_PAIRING_CODE target requires accountId");
  }

  @Test
  @DisplayName("Should require the Account when the kind is an Account password verification")
  void shouldRequireAccountWhenKindIsAccountPasswordVerification() {
    var metadata =
        CredentialAttemptMetadata.builder()
            .kind(CredentialKind.ACCOUNT_PASSWORD_VERIFICATION)
            .ipAddress(IP_ADDRESS);
    assertThatThrownBy(metadata::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ACCOUNT_PASSWORD_VERIFICATION target requires accountId");
  }

  @ParameterizedTest
  @EnumSource(
      value = CredentialKind.class,
      names = {"ACCOUNT_INVITATION_CODE", "PASSWORD_RESET_CODE", "PROFILE_MANAGER_INVITATION_CODE"})
  @DisplayName("Should reject an Account identifier when the kind is an opaque code")
  void shouldRejectAccountIdentifierWhenKindIsOpaqueCode(CredentialKind kind) {
    var metadata =
        CredentialAttemptMetadata.builder()
            .kind(kind)
            .accountId(ACCOUNT_ID)
            .credentialId(CREDENTIAL_ID)
            .ipAddress(IP_ADDRESS);
    assertThatThrownBy(metadata::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(kind + " target must not carry accountId");
  }

  @Test
  @DisplayName("Should reject a Profile identifier when the kind is a login")
  void shouldRejectProfileIdentifierWhenKindIsLogin() {
    var metadata =
        CredentialAttemptMetadata.builder()
            .kind(CredentialKind.ACCOUNT_LOGIN)
            .accountId(ACCOUNT_ID)
            .profileId(PROFILE_ID)
            .ipAddress(IP_ADDRESS);
    assertThatThrownBy(metadata::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ACCOUNT_LOGIN target must not carry profileId");
  }

  @Test
  @DisplayName("Should accept login metadata when the Account is unresolved")
  void shouldAcceptLoginMetadataWhenAccountIsUnresolved() {
    var metadata =
        CredentialAttemptMetadata.builder()
            .kind(CredentialKind.ACCOUNT_LOGIN)
            .ipAddress(IP_ADDRESS);

    assertThatCode(metadata::build).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should accept opaque code metadata when the credential is unresolved")
  void shouldAcceptOpaqueCodeMetadataWhenCredentialIsUnresolved() {
    var metadata =
        CredentialAttemptMetadata.builder()
            .kind(CredentialKind.PASSWORD_RESET_CODE)
            .ipAddress(IP_ADDRESS);

    assertThatCode(metadata::build).doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("resolvedShapes")
  @DisplayName("Should accept resolved metadata when its identifiers match the kind")
  void shouldAcceptResolvedMetadataWhenIdentifiersMatchKind(
      CredentialKind kind, CredentialAttemptMetadata.CredentialAttemptMetadataBuilder metadata) {
    assertThatCode(metadata::build).doesNotThrowAnyException();
  }

  private static Stream<Arguments> resolvedShapes() {
    return Stream.of(
        Arguments.of(
            CredentialKind.ACCOUNT_LOGIN,
            CredentialAttemptMetadata.builder()
                .kind(CredentialKind.ACCOUNT_LOGIN)
                .accountId(ACCOUNT_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.ACCOUNT_PASSWORD_VERIFICATION,
            CredentialAttemptMetadata.builder()
                .kind(CredentialKind.ACCOUNT_PASSWORD_VERIFICATION)
                .accountId(ACCOUNT_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.PROFILE_PIN,
            CredentialAttemptMetadata.builder()
                .kind(CredentialKind.PROFILE_PIN)
                .accountId(ACCOUNT_ID)
                .profileId(PROFILE_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.ACCOUNT_INVITATION_CODE,
            CredentialAttemptMetadata.builder()
                .kind(CredentialKind.ACCOUNT_INVITATION_CODE)
                .credentialId(CREDENTIAL_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.PASSWORD_RESET_CODE,
            CredentialAttemptMetadata.builder()
                .kind(CredentialKind.PASSWORD_RESET_CODE)
                .credentialId(CREDENTIAL_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.PROFILE_MANAGER_INVITATION_CODE,
            CredentialAttemptMetadata.builder()
                .kind(CredentialKind.PROFILE_MANAGER_INVITATION_CODE)
                .credentialId(CREDENTIAL_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.DEVICE_PAIRING_CODE,
            CredentialAttemptMetadata.builder()
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
      CredentialAttemptMetadata.CredentialAttemptMetadataBuilder metadata) {
    assertThatThrownBy(metadata::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(kind + " target must not carry " + identifier);
  }

  private static Stream<Arguments> forbiddenIdentifiers() {
    return Stream.of(
        Arguments.of(
            CredentialKind.ACCOUNT_LOGIN,
            "credentialId",
            CredentialAttemptMetadata.builder()
                .kind(CredentialKind.ACCOUNT_LOGIN)
                .accountId(ACCOUNT_ID)
                .credentialId(CREDENTIAL_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.ACCOUNT_PASSWORD_VERIFICATION,
            "profileId",
            CredentialAttemptMetadata.builder()
                .kind(CredentialKind.ACCOUNT_PASSWORD_VERIFICATION)
                .accountId(ACCOUNT_ID)
                .profileId(PROFILE_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.ACCOUNT_PASSWORD_VERIFICATION,
            "credentialId",
            CredentialAttemptMetadata.builder()
                .kind(CredentialKind.ACCOUNT_PASSWORD_VERIFICATION)
                .accountId(ACCOUNT_ID)
                .credentialId(CREDENTIAL_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.PROFILE_PIN,
            "credentialId",
            CredentialAttemptMetadata.builder()
                .kind(CredentialKind.PROFILE_PIN)
                .accountId(ACCOUNT_ID)
                .profileId(PROFILE_ID)
                .credentialId(CREDENTIAL_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.DEVICE_PAIRING_CODE,
            "profileId",
            CredentialAttemptMetadata.builder()
                .kind(CredentialKind.DEVICE_PAIRING_CODE)
                .accountId(ACCOUNT_ID)
                .profileId(PROFILE_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.DEVICE_PAIRING_CODE,
            "credentialId",
            CredentialAttemptMetadata.builder()
                .kind(CredentialKind.DEVICE_PAIRING_CODE)
                .accountId(ACCOUNT_ID)
                .credentialId(CREDENTIAL_ID)
                .ipAddress(IP_ADDRESS)),
        Arguments.of(
            CredentialKind.PASSWORD_RESET_CODE,
            "profileId",
            CredentialAttemptMetadata.builder()
                .kind(CredentialKind.PASSWORD_RESET_CODE)
                .profileId(PROFILE_ID)
                .credentialId(CREDENTIAL_ID)
                .ipAddress(IP_ADDRESS)));
  }
}

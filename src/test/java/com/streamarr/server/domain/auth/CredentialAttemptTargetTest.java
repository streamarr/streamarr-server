package com.streamarr.server.domain.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
    assertThatThrownBy(
            () ->
                CredentialAttemptTarget.builder()
                    .kind(CredentialKind.PROFILE_PIN)
                    .accountId(ACCOUNT_ID)
                    .ipAddress(IP_ADDRESS)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("PROFILE_PIN target requires profileId");
  }

  @Test
  @DisplayName("Should require the Account when the kind is a Profile PIN")
  void shouldRequireAccountWhenKindIsProfilePin() {
    assertThatThrownBy(
            () ->
                CredentialAttemptTarget.builder()
                    .kind(CredentialKind.PROFILE_PIN)
                    .profileId(PROFILE_ID)
                    .ipAddress(IP_ADDRESS)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("PROFILE_PIN target requires accountId");
  }

  @Test
  @DisplayName("Should require the approver's Account when the kind is a device pairing code")
  void shouldRequireApproverAccountWhenKindIsDevicePairingCode() {
    assertThatThrownBy(
            () ->
                CredentialAttemptTarget.builder()
                    .kind(CredentialKind.DEVICE_PAIRING_CODE)
                    .credentialId(CREDENTIAL_ID)
                    .ipAddress(IP_ADDRESS)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("DEVICE_PAIRING_CODE target requires accountId");
  }

  @Test
  @DisplayName("Should require the Account when the kind is an Account password verification")
  void shouldRequireAccountWhenKindIsAccountPasswordVerification() {
    assertThatThrownBy(
            () ->
                CredentialAttemptTarget.builder()
                    .kind(CredentialKind.ACCOUNT_PASSWORD_VERIFICATION)
                    .ipAddress(IP_ADDRESS)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ACCOUNT_PASSWORD_VERIFICATION target requires accountId");
  }

  @ParameterizedTest
  @EnumSource(
      value = CredentialKind.class,
      names = {"ACCOUNT_INVITATION_CODE", "PASSWORD_RESET_CODE", "PROFILE_MANAGER_INVITATION_CODE"})
  @DisplayName("Should reject an Account identifier when the kind is an opaque code")
  void shouldRejectAccountIdentifierWhenKindIsOpaqueCode(CredentialKind kind) {
    assertThatThrownBy(
            () ->
                CredentialAttemptTarget.builder()
                    .kind(kind)
                    .accountId(ACCOUNT_ID)
                    .credentialId(CREDENTIAL_ID)
                    .ipAddress(IP_ADDRESS)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(kind + " target must not carry accountId");
  }

  @Test
  @DisplayName("Should reject a Profile identifier when the kind is a login")
  void shouldRejectProfileIdentifierWhenKindIsLogin() {
    assertThatThrownBy(
            () ->
                CredentialAttemptTarget.builder()
                    .kind(CredentialKind.ACCOUNT_LOGIN)
                    .accountId(ACCOUNT_ID)
                    .profileId(PROFILE_ID)
                    .ipAddress(IP_ADDRESS)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ACCOUNT_LOGIN target must not carry profileId");
  }

  @Test
  @DisplayName("Should accept an unresolved login and an unresolved opaque code")
  void shouldAcceptUnresolvedLoginAndUnresolvedOpaqueCode() {
    assertThatCode(
            () ->
                CredentialAttemptTarget.builder()
                    .kind(CredentialKind.ACCOUNT_LOGIN)
                    .ipAddress(IP_ADDRESS)
                    .build())
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                CredentialAttemptTarget.builder()
                    .kind(CredentialKind.PASSWORD_RESET_CODE)
                    .ipAddress(IP_ADDRESS)
                    .build())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should accept every resolved shape when the identifiers match the kind")
  void shouldAcceptEveryResolvedShapeWhenIdentifiersMatchKind() {
    assertThatCode(
            () -> {
              CredentialAttemptTarget.builder()
                  .kind(CredentialKind.ACCOUNT_LOGIN)
                  .accountId(ACCOUNT_ID)
                  .ipAddress(IP_ADDRESS)
                  .build();
              CredentialAttemptTarget.builder()
                  .kind(CredentialKind.ACCOUNT_PASSWORD_VERIFICATION)
                  .accountId(ACCOUNT_ID)
                  .ipAddress(IP_ADDRESS)
                  .build();
              CredentialAttemptTarget.builder()
                  .kind(CredentialKind.PROFILE_PIN)
                  .accountId(ACCOUNT_ID)
                  .profileId(PROFILE_ID)
                  .ipAddress(IP_ADDRESS)
                  .build();
              CredentialAttemptTarget.builder()
                  .kind(CredentialKind.ACCOUNT_INVITATION_CODE)
                  .credentialId(CREDENTIAL_ID)
                  .ipAddress(IP_ADDRESS)
                  .build();
              CredentialAttemptTarget.builder()
                  .kind(CredentialKind.DEVICE_PAIRING_CODE)
                  .accountId(ACCOUNT_ID)
                  .ipAddress(IP_ADDRESS)
                  .build();
            })
        .doesNotThrowAnyException();
  }
}

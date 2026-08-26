package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.controllers.auth.AcceptInvitationRequest;
import com.streamarr.server.controllers.auth.AuthTokenResponseWriter;
import com.streamarr.server.controllers.auth.InvitationCodeRequest;
import com.streamarr.server.controllers.auth.RedeemPasswordResetRequest;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.graphql.dto.AccountInvitationDetails;
import com.streamarr.server.graphql.dto.IssuedAccountInvitation;
import com.streamarr.server.graphql.dto.IssuedPasswordReset;
import com.streamarr.server.services.identity.CredentialIssuanceService;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

@Tag("UnitTest")
@DisplayName("Credential Secret Redaction Tests")
class CredentialSecretRedactionTest {

  private static final String CODE = "public-id.raw-code-secret";
  private static final String PASSWORD = "raw-password-secret";
  private static final String REFRESH_TOKEN = "raw-refresh-token-secret";

  @ParameterizedTest(name = "Should redact {0} when its value is rendered as text")
  @MethodSource("secretBearingValues")
  @DisplayName("Should redact every credential secret when its value is rendered as text")
  void shouldRedactEveryCredentialSecretWhenValueIsRenderedAsText(RedactionCase redactionCase) {
    assertThat(redactionCase.rendered())
        .contains(redactionCase.redactionMarker())
        .doesNotContain(redactionCase.secret());
  }

  private static Stream<RedactionCase> secretBearingValues() {
    var invitationId = UUID.randomUUID();
    var resetCodeId = UUID.randomUUID();
    var accountId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();
    var digest = new byte[] {11, 22, 33, 44};
    var invitation =
        AccountInvitation.builder()
            .id(invitationId)
            .publicId("public-id")
            .secretDigest(digest)
            .build();
    var resetCode =
        PasswordResetCode.builder()
            .id(resetCodeId)
            .publicId("reset-public-id")
            .secretDigest(digest)
            .build();
    var invitationDetails = AccountInvitationDetails.builder().id(invitationId).build();
    var acceptRequest =
        AcceptInvitationRequest.builder()
            .code(CODE)
            .displayName("Invitee")
            .password(PASSWORD)
            .cookieMode(false)
            .build();
    var redeemRequest = new RedeemPasswordResetRequest(CODE, PASSWORD);
    var accessToken = new AccessToken("access-token-secret", Instant.now(), TokenScope.ACCOUNT);
    var refreshResponse =
        AuthTokenResponseWriter.RefreshResponse.builder()
            .status(HttpStatus.OK)
            .accessToken(accessToken)
            .rawRefreshToken(REFRESH_TOKEN)
            .cookieMode(false)
            .build();

    return Stream.of(
        redaction(
            "token response refresh token",
            refreshResponse,
            secret("rawRefreshToken=REDACTED", REFRESH_TOKEN)),
        redaction(
            "token response builder",
            AuthTokenResponseWriter.RefreshResponse.builder().rawRefreshToken(REFRESH_TOKEN),
            secret("rawRefreshToken=REDACTED", REFRESH_TOKEN)),
        redaction(
            "invitation request builder code",
            AcceptInvitationRequest.builder().code(CODE).password(PASSWORD),
            secret("code=REDACTED", CODE)),
        redaction(
            "invitation request builder password",
            AcceptInvitationRequest.builder().code(CODE).password(PASSWORD),
            secret("password=REDACTED", PASSWORD)),
        redaction(
            "invitation acceptance command builder code",
            AccountInvitationService.AcceptInvitationCommand.builder()
                .code(CODE)
                .password(PASSWORD),
            secret("code=REDACTED", CODE)),
        redaction(
            "invitation acceptance command builder password",
            AccountInvitationService.AcceptInvitationCommand.builder()
                .code(CODE)
                .password(PASSWORD),
            secret("password=REDACTED", PASSWORD)),
        redaction(
            "accepted invitation builder",
            AccountInvitationService.AcceptedInvitation.builder().rawRefreshToken(REFRESH_TOKEN),
            secret("rawRefreshToken=REDACTED", REFRESH_TOKEN)),
        redaction("invitation request code", acceptRequest, secret("code=REDACTED", CODE)),
        redaction(
            "invitation request password", acceptRequest, secret("password=REDACTED", PASSWORD)),
        redaction(
            "invitation code request",
            new InvitationCodeRequest(CODE),
            secret("code=REDACTED", CODE)),
        redaction("reset request code", redeemRequest, secret("code=REDACTED", CODE)),
        redaction(
            "reset request password", redeemRequest, secret("newPassword=REDACTED", PASSWORD)),
        redaction(
            "invitation digest",
            invitation,
            secret("secretDigest=REDACTED", Arrays.toString(digest))),
        redaction(
            "password-reset digest",
            resetCode,
            secret("secretDigest=REDACTED", Arrays.toString(digest))),
        redaction(
            "issued GraphQL invitation",
            new IssuedAccountInvitation(invitationDetails, CODE),
            secret("code=REDACTED", CODE)),
        redaction(
            "issued GraphQL password reset",
            new IssuedPasswordReset(accountId, CODE, Instant.now().toString()),
            secret("code=REDACTED", CODE)),
        redaction(
            "invitation acceptance command",
            AccountInvitationService.AcceptInvitationCommand.builder()
                .code(CODE)
                .displayName("Invitee")
                .password(PASSWORD)
                .deviceName("test")
                .build(),
            secret("code=REDACTED", CODE)),
        redaction(
            "invitation acceptance password",
            AccountInvitationService.AcceptInvitationCommand.builder()
                .code(CODE)
                .displayName("Invitee")
                .password(PASSWORD)
                .deviceName("test")
                .build(),
            secret("password=REDACTED", PASSWORD)),
        redaction(
            "accepted invitation refresh token",
            AccountInvitationService.AcceptedInvitation.builder()
                .account(UserAccount.builder().id(accountId).build())
                .session(AuthSession.builder().id(sessionId).build())
                .rawRefreshToken(REFRESH_TOKEN)
                .build(),
            secret("rawRefreshToken=REDACTED", REFRESH_TOKEN)),
        redaction(
            "issued service invitation",
            new CredentialIssuanceService.IssuedInvitation(invitation, CODE),
            secret("code=REDACTED", CODE)),
        redaction(
            "issued service password reset",
            new CredentialIssuanceService.IssuedResetCode(resetCode, CODE),
            secret("code=REDACTED", CODE)));
  }

  private static RedactionCase redaction(
      String description, Object value, RedactedSecret redactedSecret) {
    return RedactionCase.builder()
        .description(description)
        .rendered(value.toString())
        .redactionMarker(redactedSecret.marker())
        .secret(redactedSecret.value())
        .build();
  }

  private static RedactedSecret secret(String marker, String value) {
    return new RedactedSecret(marker, value);
  }

  @lombok.Builder
  private record RedactionCase(
      String description, String rendered, String redactionMarker, String secret) {
    @Override
    public String toString() {
      return description;
    }
  }

  private record RedactedSecret(String marker, String value) {}
}

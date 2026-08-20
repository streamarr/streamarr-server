package com.streamarr.server.controllers.auth;

import com.streamarr.server.services.auth.AccessTokenIssuer;
import com.streamarr.server.services.auth.AccountInvitationCeremonyService;
import com.streamarr.server.services.auth.AccountInvitationCeremonyService.AcceptInvitationCommand;
import com.streamarr.server.services.auth.AccountInvitationCeremonyService.InvitationPreview;
import com.streamarr.server.services.auth.TokenContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The principal-less invitation ceremonies (ADR 0024 §Invitations): the recipient has no Account
 * yet, so these are REST, authenticated by the code alone. POST keeps codes out of URLs and logs.
 */
@RestController
@RequestMapping("/api/auth/invitation")
@RequiredArgsConstructor
public class InvitationController {

  private final AccountInvitationCeremonyService ceremonyService;
  private final AccessTokenIssuer accessTokenIssuer;
  private final AuthCookieWriter cookieWriter;

  @PostMapping("/lookup")
  public InvitationPreview lookup(@Valid @RequestBody InvitationCodeRequest request) {
    return ceremonyService.lookup(request.code());
  }

  @PostMapping("/accept")
  public ResponseEntity<AuthTokensResponse> accept(
      @Valid @RequestBody AcceptInvitationRequest request, HttpServletRequest httpRequest) {
    var accepted =
        ceremonyService.accept(
            AcceptInvitationCommand.builder()
                .code(request.code())
                .displayName(request.displayName())
                .password(request.password())
                .deviceName(httpRequest.getHeader(HttpHeaders.USER_AGENT))
                .build());
    var accessToken =
        accessTokenIssuer.issue(TokenContext.of(accepted.account(), accepted.session()));

    var body =
        AuthTokensResponse.builder()
            .accessTokenExpiresAt(accessToken.expiresAt())
            .scope(accessToken.scope().claimValue());
    if (Boolean.TRUE.equals(request.cookieMode())) {
      return ResponseEntity.status(HttpStatus.CREATED)
          .header(HttpHeaders.SET_COOKIE, cookieWriter.accessCookie(accessToken.value()).toString())
          .header(
              HttpHeaders.SET_COOKIE,
              cookieWriter.refreshCookie(accepted.rawRefreshToken()).toString())
          .body(body.build());
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            body.accessToken(accessToken.value()).refreshToken(accepted.rawRefreshToken()).build());
  }

  @PostMapping("/decline")
  public ResponseEntity<Void> decline(@Valid @RequestBody InvitationCodeRequest request) {
    ceremonyService.decline(request.code());
    return ResponseEntity.noContent().build();
  }
}

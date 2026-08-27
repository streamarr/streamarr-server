package com.streamarr.server.controllers.auth;

import com.streamarr.server.services.auth.AccessTokenIssuer;
import com.streamarr.server.services.auth.AccountInvitationService;
import com.streamarr.server.services.auth.AccountInvitationService.AcceptInvitationCommand;
import com.streamarr.server.services.auth.DeviceName;
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

  private final AccountInvitationService invitationService;
  private final AccessTokenIssuer accessTokenIssuer;
  private final AuthTokenResponseWriter tokenResponseWriter;

  @PostMapping("/lookup")
  public InvitationLookupResponse lookup(@Valid @RequestBody InvitationCodeRequest request) {
    return InvitationLookupResponse.from(invitationService.lookup(request.code()));
  }

  @PostMapping("/accept")
  public ResponseEntity<AuthTokensResponse> accept(
      @Valid @RequestBody AcceptInvitationRequest request, HttpServletRequest httpRequest) {
    var accepted =
        invitationService.accept(
            AcceptInvitationCommand.builder()
                .code(request.code())
                .displayName(request.displayName())
                .password(request.password())
                .deviceName(DeviceName.sanitize(httpRequest.getHeader(HttpHeaders.USER_AGENT)))
                .build());
    var accessToken =
        accessTokenIssuer.issue(TokenContext.of(accepted.account(), accepted.session()));

    return tokenResponseWriter.withRefreshToken(
        AuthTokenResponseWriter.RefreshResponse.builder()
            .status(HttpStatus.CREATED)
            .accessToken(accessToken)
            .rawRefreshToken(accepted.rawRefreshToken())
            .cookieMode(Boolean.TRUE.equals(request.cookieMode()))
            .build());
  }

  @PostMapping("/decline")
  public ResponseEntity<Void> decline(@Valid @RequestBody InvitationCodeRequest request) {
    invitationService.decline(request.code());
    return ResponseEntity.noContent().build();
  }
}

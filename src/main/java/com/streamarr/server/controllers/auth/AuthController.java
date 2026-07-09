package com.streamarr.server.controllers.auth;

import com.streamarr.server.exceptions.InvalidRefreshTokenException;
import com.streamarr.server.services.auth.AccessToken;
import com.streamarr.server.services.auth.AccessTokenIssuer;
import com.streamarr.server.services.auth.ChangePasswordCommand;
import com.streamarr.server.services.auth.LoginCommand;
import com.streamarr.server.services.auth.LoginService;
import com.streamarr.server.services.auth.PasswordChangeService;
import com.streamarr.server.services.auth.RefreshTokenService;
import com.streamarr.server.services.auth.SessionScopeService;
import com.streamarr.server.services.auth.SetupCommand;
import com.streamarr.server.services.auth.SetupService;
import com.streamarr.server.services.auth.TokenRefreshService;
import com.streamarr.server.services.authorization.AuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private final SetupService setupService;
  private final LoginService loginService;
  private final RefreshTokenService refreshTokenService;
  private final TokenRefreshService tokenRefreshService;
  private final SessionScopeService sessionScopeService;
  private final AccessTokenIssuer accessTokenIssuer;
  private final AuthorizationService authorizationService;
  private final PasswordChangeService passwordChangeService;
  private final AuthCookieWriter cookieWriter;

  @org.springframework.web.bind.annotation.GetMapping("/status")
  public StatusResponse status() {
    return new StatusResponse(setupService.isSetupComplete());
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout() {
    var identity = authorizationService.currentIdentity();
    refreshTokenService.logout(identity.sessionId());

    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookieWriter.expiredAccessCookie().toString())
        .header(HttpHeaders.SET_COOKIE, cookieWriter.expiredRefreshCookie().toString())
        .build();
  }

  @PostMapping("/change-password")
  public ResponseEntity<AuthTokensResponse> changePassword(
      @Valid @RequestBody ChangePasswordRequest request) {
    var identity = authorizationService.currentIdentity();
    var result =
        passwordChangeService.changePassword(
            ChangePasswordCommand.builder()
                .accountId(identity.accountId())
                .sessionId(identity.sessionId())
                .currentPassword(request.currentPassword())
                .newPassword(request.newPassword())
                .build());

    var context = sessionScopeService.revalidateStoredContext(result.account(), result.session());
    var accessToken = accessTokenIssuer.issue(context);

    return respond(HttpStatus.OK, accessToken, result.rawRefreshToken(), request.cookieMode());
  }

  @PostMapping("/select-household")
  public ResponseEntity<AuthTokensResponse> selectHousehold(
      @Valid @RequestBody SelectHouseholdRequest request) {
    var identity = authorizationService.currentIdentity();
    var context =
        sessionScopeService.selectHousehold(
            identity.accountId(), identity.sessionId(), request.householdId());
    return respondAccessOnly(accessTokenIssuer.issue(context), request.cookieMode());
  }

  @PostMapping("/select-profile")
  public ResponseEntity<AuthTokensResponse> selectProfile(
      @Valid @RequestBody SelectProfileRequest request) {
    var identity = authorizationService.currentIdentity();
    var context =
        sessionScopeService.selectProfile(
            identity.accountId(), identity.sessionId(), request.profileId());
    return respondAccessOnly(accessTokenIssuer.issue(context), request.cookieMode());
  }

  @PostMapping("/setup")
  public ResponseEntity<AuthTokensResponse> setup(
      @Valid @RequestBody SetupRequest request, HttpServletRequest httpRequest) {
    var result =
        setupService.setup(
            SetupCommand.builder()
                .email(request.email())
                .displayName(request.displayName())
                .password(request.password())
                .householdName(request.householdName())
                .profileName(request.profileName())
                .build());

    var issued = refreshTokenService.createSession(result.admin(), deviceNameOf(httpRequest));
    var context = sessionScopeService.autoSelectContext(result.admin(), issued.session());
    var accessToken = accessTokenIssuer.issue(context);

    return respond(HttpStatus.CREATED, accessToken, issued.rawToken(), request.cookieMode());
  }

  @PostMapping("/login")
  public ResponseEntity<AuthTokensResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
    var result =
        loginService.login(
            LoginCommand.builder()
                .email(request.email())
                .password(request.password())
                .deviceName(request.deviceName())
                .source(httpRequest.getRemoteAddr())
                .build());

    var context = sessionScopeService.autoSelectContext(result.account(), result.session());
    var accessToken = accessTokenIssuer.issue(context);

    return respond(HttpStatus.OK, accessToken, result.rawRefreshToken(), request.cookieMode());
  }

  @PostMapping("/refresh")
  public ResponseEntity<AuthTokensResponse> refresh(
      @RequestBody(required = false) RefreshRequest request, HttpServletRequest httpRequest) {
    var carrier = resolveRefreshCarrier(request, httpRequest);

    var refreshed = tokenRefreshService.refresh(carrier.rawToken());

    // Only a genuine rotation carries a successor refresh token; a grace replay never does —
    // exactly one refresh cookie per grace episode, so late responses cannot poison the jar.
    if (refreshed.rotated()) {
      return respond(
          HttpStatus.OK,
          refreshed.accessToken(),
          refreshed.rotatedRefreshToken(),
          carrier.cookieMode());
    }
    return respondAccessOnly(refreshed.accessToken(), carrier.cookieMode());
  }

  private RefreshCarrier resolveRefreshCarrier(
      RefreshRequest request, HttpServletRequest httpRequest) {
    if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
      return new RefreshCarrier(request.refreshToken(), request.cookieMode());
    }

    var cookies = httpRequest.getCookies();
    if (cookies == null) {
      throw new InvalidRefreshTokenException();
    }

    return Arrays.stream(cookies)
        .filter(cookie -> AuthCookieWriter.REFRESH_COOKIE.equals(cookie.getName()))
        .map(jakarta.servlet.http.Cookie::getValue)
        .findFirst()
        .map(rawToken -> new RefreshCarrier(rawToken, true))
        .orElseThrow(InvalidRefreshTokenException::new);
  }

  private static String deviceNameOf(HttpServletRequest httpRequest) {
    return httpRequest.getHeader(HttpHeaders.USER_AGENT);
  }

  private ResponseEntity<AuthTokensResponse> respond(
      HttpStatus status, AccessToken accessToken, String rawRefreshToken, boolean cookieMode) {
    var body =
        AuthTokensResponse.builder()
            .accessTokenExpiresAt(accessToken.expiresAt())
            .scope(accessToken.scope().claimValue());

    if (!cookieMode) {
      return ResponseEntity.status(status)
          .body(body.accessToken(accessToken.value()).refreshToken(rawRefreshToken).build());
    }

    return ResponseEntity.status(status)
        .header(HttpHeaders.SET_COOKIE, cookieWriter.accessCookie(accessToken.value()).toString())
        .header(HttpHeaders.SET_COOKIE, cookieWriter.refreshCookie(rawRefreshToken).toString())
        .body(body.build());
  }

  /** Grace replays refresh the access credential only; the refresh cookie is never rewritten. */
  private ResponseEntity<AuthTokensResponse> respondAccessOnly(
      AccessToken accessToken, boolean cookieMode) {
    var body =
        AuthTokensResponse.builder()
            .accessTokenExpiresAt(accessToken.expiresAt())
            .scope(accessToken.scope().claimValue());

    if (!cookieMode) {
      return ResponseEntity.ok(body.accessToken(accessToken.value()).build());
    }

    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookieWriter.accessCookie(accessToken.value()).toString())
        .body(body.build());
  }

  private record RefreshCarrier(String rawToken, boolean cookieMode) {}
}

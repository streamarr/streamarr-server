package com.streamarr.server.controllers.auth;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import com.streamarr.server.config.security.StreamarrBearerTokenResolver;
import com.streamarr.server.exceptions.InvalidRefreshTokenException;
import com.streamarr.server.services.auth.AccessTokenIssuer;
import com.streamarr.server.services.auth.ChangePasswordCommand;
import com.streamarr.server.services.auth.DeviceAuthorizationService;
import com.streamarr.server.services.auth.LoginCommand;
import com.streamarr.server.services.auth.LoginService;
import com.streamarr.server.services.auth.PasswordChangeService;
import com.streamarr.server.services.auth.ReauthenticationService;
import com.streamarr.server.services.auth.RefreshTokenService;
import com.streamarr.server.services.auth.SetupCommand;
import com.streamarr.server.services.auth.SetupService;
import com.streamarr.server.services.auth.TokenContext;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.HouseholdContextService;
import com.streamarr.server.services.identity.ProfileSelectionService;
import com.streamarr.server.services.identity.SelectProfileCommand;
import com.streamarr.server.services.identity.SessionContextService;
import com.streamarr.server.services.identity.TokenRefreshService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
  private final SessionContextService sessionContextService;
  private final HouseholdContextService householdContextService;
  private final ProfileSelectionService profileSelectionService;
  private final AccessTokenIssuer accessTokenIssuer;
  private final AuthorizationService authorizationService;
  private final PasswordChangeService passwordChangeService;
  private final ReauthenticationService reauthenticationService;
  private final DeviceAuthorizationService deviceAuthorizationService;
  private final AuthCookieWriter cookieWriter;
  private final AuthTokenResponseWriter tokenResponseWriter;

  /**
   * The bootstrap a client reads before it can do anything else. Both flags are required in v1: a
   * client that cannot see whether pairing is available would loop against a 503 forever.
   */
  @GetMapping("/status")
  public ResponseEntity<StatusResponse> status() {
    return ResponseEntity.ok()
        .body(
            new StatusResponse(
                setupService.isSetupComplete(), deviceAuthorizationService.isPairingEnabled()));
  }

  @PostMapping("/refresh/revoke")
  public ResponseEntity<Void> logout(
      @RequestBody(required = false) RefreshRequest request, HttpServletRequest httpRequest) {
    var carrier = resolveRefreshCarrier(request, httpRequest);
    refreshTokenService.logout(carrier.refreshToken());

    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookieWriter.expiredAccessCookie().toString())
        .header(HttpHeaders.SET_COOKIE, cookieWriter.expiredRefreshCookie().toString())
        .build();
  }

  @PostMapping("/change-password")
  public ResponseEntity<AuthTokensResponse> changePassword(
      @Valid @RequestBody ChangePasswordRequest request, HttpServletRequest httpRequest) {
    var identity = authorizationService.currentIdentity();
    var result =
        passwordChangeService.changePassword(
            ChangePasswordCommand.builder()
                .accountId(identity.accountId())
                .sessionId(identity.authSessionId())
                .currentPassword(request.currentPassword())
                .newPassword(request.newPassword())
                .build());

    var context = sessionContextService.revalidateStoredContext(result.account(), result.session());
    var accessToken = accessTokenIssuer.issue(context);

    return tokenResponseWriter.withRefreshToken(
        AuthTokenResponseWriter.RefreshResponse.builder()
            .status(HttpStatus.OK)
            .accessToken(accessToken)
            .rawRefreshToken(result.rawRefreshToken())
            .cookieMode(StreamarrBearerTokenResolver.usedAccessCookie(httpRequest))
            .build());
  }

  @PostMapping("/select-household")
  public ResponseEntity<AuthTokensResponse> selectHousehold(
      @Valid @RequestBody SelectHouseholdRequest request, HttpServletRequest httpRequest) {
    var identity = authorizationService.currentIdentity();
    var context =
        householdContextService.selectHousehold(
            identity.accountId(), identity.authSessionId(), request.householdId());
    return tokenResponseWriter.accessOnly(
        accessTokenIssuer.issueDerived(
            context.withReauthenticatedAt(identity.reauthenticatedAt()),
            authorizationService.currentTokenExpiry()),
        StreamarrBearerTokenResolver.usedAccessCookie(httpRequest));
  }

  @PostMapping("/reauth")
  public ResponseEntity<AuthTokensResponse> reauthenticate(
      @Valid @RequestBody ReauthRequest request, HttpServletRequest httpRequest) {
    var identity = authorizationService.currentIdentity();
    var context = reauthenticationService.reauthenticate(identity, request.password());
    return tokenResponseWriter.accessOnly(
        accessTokenIssuer.issueReauthenticated(context, authorizationService.currentTokenExpiry()),
        StreamarrBearerTokenResolver.usedAccessCookie(httpRequest));
  }

  @PostMapping("/select-profile")
  public ResponseEntity<AuthTokensResponse> selectProfile(
      @Valid @RequestBody SelectProfileRequest request, HttpServletRequest httpRequest) {
    var identity = authorizationService.currentIdentity();
    var context =
        profileSelectionService.selectProfile(
            identity,
            SelectProfileCommand.builder()
                .profileId(request.profileId())
                .pin(request.pin())
                .build());
    return tokenResponseWriter.accessOnly(
        accessTokenIssuer.issueDerived(
            context.withReauthenticatedAt(identity.reauthenticatedAt()),
            authorizationService.currentTokenExpiry()),
        StreamarrBearerTokenResolver.usedAccessCookie(httpRequest));
  }

  @PostMapping(value = "/setup", consumes = APPLICATION_JSON_VALUE)
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
    var accessToken = accessTokenIssuer.issue(TokenContext.of(result.admin(), issued.session()));

    return tokenResponseWriter.withRefreshToken(
        AuthTokenResponseWriter.RefreshResponse.builder()
            .status(HttpStatus.CREATED)
            .accessToken(accessToken)
            .rawRefreshToken(issued.rawToken())
            .cookieMode(request.cookieMode())
            .build());
  }

  @PostMapping(value = "/login", consumes = APPLICATION_JSON_VALUE)
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

    // A new session starts in the membership Household at the Profile picker (Account scope).
    var accessToken = accessTokenIssuer.issue(TokenContext.of(result.account(), result.session()));

    return tokenResponseWriter.withRefreshToken(
        AuthTokenResponseWriter.RefreshResponse.builder()
            .status(HttpStatus.OK)
            .accessToken(accessToken)
            .rawRefreshToken(result.rawRefreshToken())
            .cookieMode(request.cookieMode())
            .build());
  }

  @PostMapping(value = "/refresh", consumes = APPLICATION_JSON_VALUE)
  public ResponseEntity<AuthTokensResponse> refresh(
      @RequestBody(required = false) RefreshRequest request, HttpServletRequest httpRequest) {
    var carrier = resolveRefreshCarrier(request, httpRequest);

    var refreshed = tokenRefreshService.refresh(carrier.refreshToken());

    if (refreshed.carriesRefreshToken()) {
      return tokenResponseWriter.withRefreshToken(
          AuthTokenResponseWriter.RefreshResponse.builder()
              .status(HttpStatus.OK)
              .accessToken(refreshed.accessToken())
              .rawRefreshToken(refreshed.rawRefreshToken())
              .cookieMode(carrier.cookieMode())
              .build());
    }

    return tokenResponseWriter.accessOnly(refreshed.accessToken(), carrier.cookieMode());
  }

  private RefreshCarrier resolveRefreshCarrier(
      RefreshRequest request, HttpServletRequest httpRequest) {
    if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
      return new RefreshCarrier(request.refreshToken(), false);
    }

    var cookies = httpRequest.getCookies();
    if (cookies == null) {
      throw new InvalidRefreshTokenException();
    }

    return Arrays.stream(cookies)
        .filter(cookie -> AuthCookieWriter.REFRESH_COOKIE.equals(cookie.getName()))
        .map(Cookie::getValue)
        .findFirst()
        .map(rawToken -> new RefreshCarrier(rawToken, true))
        .orElseThrow(InvalidRefreshTokenException::new);
  }

  private record RefreshCarrier(String refreshToken, boolean cookieMode) {}

  private static String deviceNameOf(HttpServletRequest httpRequest) {
    return httpRequest.getHeader(HttpHeaders.USER_AGENT);
  }
}

package com.streamarr.server.controllers.auth.device;

import com.streamarr.server.controllers.auth.AuthErrorResponse;
import com.streamarr.server.controllers.auth.AuthTokensResponse;
import com.streamarr.server.exceptions.InvalidDecisionException;
import com.streamarr.server.services.auth.DeviceAuthorizationDetails;
import com.streamarr.server.services.auth.DeviceAuthorizationService;
import com.streamarr.server.services.auth.DeviceDecision;
import com.streamarr.server.services.auth.DevicePollResult;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.DevicePairingService;
import java.util.Arrays;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RFC 8628 semantics over Streamarr transport (ADR 0021). REST rather than GraphQL because these
 * endpoints mint tokens, carry per-state HTTP status codes and headers, and are polled by a device
 * that has no session yet — the same carve-out ADR 0002 already makes for token issuance.
 */
@RestController
@RequestMapping("/api/auth/device")
@RequiredArgsConstructor
public class DeviceAuthController {

  private final DeviceAuthorizationService deviceAuthorizationService;
  private final DevicePairingService devicePairingService;
  private final AuthorizationService authorizationService;

  @PostMapping("/code")
  public ResponseEntity<DeviceCodeResponse> issue(
      @RequestBody(required = false) DeviceCodeRequest request) {
    var issued =
        deviceAuthorizationService.issue(
            request == null ? null : request.deviceName(), request == null ? null : request.esn());

    return ResponseEntity.ok()
        .body(
            DeviceCodeResponse.builder()
                .deviceCode(issued.deviceCode())
                .userCode(issued.userCode())
                .verificationUri(issued.verificationUri())
                .interval(issued.interval())
                .expiresIn(issued.expiresIn())
                .build());
  }

  /**
   * Every grant-state outcome is HTTP 400 with a lowercase code — never 401 or 403, so a generic
   * networking layer can never mistake "not approved yet" for an authentication failure and log the
   * user out.
   */
  @PostMapping("/token")
  public ResponseEntity<Object> poll(@RequestBody DeviceTokenRequest request) {
    return switch (deviceAuthorizationService.redeem(request.deviceCode())) {
      case DevicePollResult.Success(var accessToken, var rawRefreshToken) ->
          ResponseEntity.ok()
              .<Object>body(
                  AuthTokensResponse.builder()
                      .accessToken(accessToken.value())
                      .accessTokenExpiresAt(accessToken.expiresAt())
                      .scope(accessToken.scope().claimValue())
                      .refreshToken(rawRefreshToken)
                      .build());
      case DevicePollResult.Pending _ ->
          pollState("authorization_pending", "The device authorization has not been approved yet.");
      case DevicePollResult.SlowDown _ ->
          pollState("slow_down", "Polling too frequently; increase the interval by five seconds.");
      case DevicePollResult.Denied _ ->
          pollState("access_denied", "The device authorization request was denied.");
      case DevicePollResult.Expired _ ->
          pollState("expired_token", "The device code is unknown or no longer usable.");
    };
  }

  @PostMapping("/authorizations/lookup")
  public ResponseEntity<DeviceAuthorizationResponse> lookup(
      @RequestBody DeviceLookupRequest request) {
    var lookup =
        devicePairingService.lookup(authorizationService.currentIdentity(), request.userCode());
    var view = lookup.authorization();

    return ResponseEntity.ok()
        .body(
            DeviceAuthorizationResponse.builder()
                .userCode(view.userCode())
                .deviceName(view.deviceName())
                .status(view.status().name())
                .requestedAt(view.requestedAt())
                .households(
                    lookup.households().stream()
                        .map(
                            household ->
                                new DeviceAuthorizationResponse.EligibleHousehold(
                                    household.id().toString(), household.name()))
                        .toList())
                .build());
  }

  @PostMapping("/authorizations/decision")
  public ResponseEntity<DeviceDecisionResponse> decide(@RequestBody DeviceDecisionRequest request) {
    var view =
        devicePairingService.decide(
            authorizationService.currentIdentity(),
            DevicePairingService.PairingDecisionCommand.builder()
                .userCode(request.userCode())
                .decision(parseDecision(request.decision()))
                .householdId(request.householdId())
                .build());

    return ResponseEntity.ok().body(decisionResponseOf(view));
  }

  /**
   * Validated before any code lookup, so a malformed request never spends guessing budget or
   * reveals whether a code exists.
   */
  private static DeviceDecision parseDecision(String decision) {
    if (decision == null) {
      throw new InvalidDecisionException();
    }

    return Arrays.stream(DeviceDecision.values())
        .filter(value -> value.name().equals(decision.strip().toUpperCase(Locale.ROOT)))
        .findFirst()
        .orElseThrow(InvalidDecisionException::new);
  }

  private static DeviceDecisionResponse decisionResponseOf(DeviceAuthorizationDetails details) {
    return new DeviceDecisionResponse(details.status().name(), details.deviceName());
  }

  private static ResponseEntity<Object> pollState(String code, String message) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new AuthErrorResponse(code, message));
  }
}

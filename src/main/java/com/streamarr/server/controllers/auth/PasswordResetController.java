package com.streamarr.server.controllers.auth;

import com.streamarr.server.services.auth.PasswordResetService;
import com.streamarr.server.services.auth.RedeemPasswordResetCommand;
import com.streamarr.server.web.ClientIpAddressResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Redeeming a password-reset code (ADR 0024 §Account): a principal-less REST ceremony, allowed
 * while the Account is disabled. It changes the password, revokes every refresh session, and
 * creates no session — the person signs in fresh, or stays disabled until re-enabled.
 */
@RestController
@RequestMapping("/api/auth/password-reset")
@RequiredArgsConstructor
public class PasswordResetController {

  private final PasswordResetService passwordResetService;
  private final ClientIpAddressResolver clientIpAddress;

  @PostMapping("/redeem")
  public ResponseEntity<Void> redeem(@Valid @RequestBody RedeemPasswordResetRequest request) {
    passwordResetService.redeem(
        RedeemPasswordResetCommand.builder()
            .code(request.code())
            .newPassword(request.newPassword())
            .ipAddress(clientIpAddress.resolve())
            .build());
    return ResponseEntity.noContent().build();
  }
}

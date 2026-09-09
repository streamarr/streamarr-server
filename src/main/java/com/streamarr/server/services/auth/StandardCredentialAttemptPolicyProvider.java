package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.CredentialAttemptPolicy;
import com.streamarr.server.domain.auth.CredentialKind;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class StandardCredentialAttemptPolicyProvider implements CredentialAttemptPolicyProvider {

  private static final CredentialAttemptPolicy.Limited STANDARD_POLICY =
      CredentialAttemptPolicy.Limited.builder()
          .maximumFailures(5)
          .failureWindow(Duration.ofMinutes(15))
          .throttleDuration(Duration.ofMinutes(15))
          .resetFailuresOnSuccess(true)
          .build();

  private static final CredentialAttemptPolicy PAIRING_POLICY =
      STANDARD_POLICY.toBuilder().resetFailuresOnSuccess(false).build();

  /**
   * Pairing codes are publicly issuable, so presenting a known one cannot forgive the approver's
   * previous guesses. Other credentials reset their own target's failures on success.
   */
  @Override
  public CredentialAttemptPolicy policyFor(CredentialKind kind) {
    return switch (kind) {
      case ACCOUNT_LOGIN,
          ACCOUNT_PASSWORD_VERIFICATION,
          PROFILE_PIN,
          ACCOUNT_INVITATION_CODE,
          PASSWORD_RESET_CODE,
          PROFILE_MANAGER_INVITATION_CODE ->
          STANDARD_POLICY;
      case DEVICE_PAIRING_CODE -> PAIRING_POLICY;
    };
  }
}

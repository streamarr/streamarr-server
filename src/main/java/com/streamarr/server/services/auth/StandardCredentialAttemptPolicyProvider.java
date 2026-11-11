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

  private static final CredentialAttemptPolicy SUCCESS_KEEPS_FAILURES_POLICY =
      STANDARD_POLICY.toBuilder().resetFailuresOnSuccess(false).build();

  /**
   * Pairing codes are publicly issuable, and anyone who holds one live share code could look it up
   * between guesses, so a success keeps the earlier failures of the Account that presents either
   * (ADR 0030). Other credential kinds reset failures on success.
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
      case DEVICE_PAIRING_CODE, SHARE_CODE -> SUCCESS_KEEPS_FAILURES_POLICY;
    };
  }
}

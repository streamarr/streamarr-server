package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.CredentialAttemptPolicy;
import com.streamarr.server.domain.auth.CredentialKind;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class StandardCredentialAttemptPolicyProvider implements CredentialAttemptPolicyProvider {

  private static final CredentialAttemptPolicy STANDARD_POLICY =
      new CredentialAttemptPolicy.Limited(5, Duration.ofMinutes(15), Duration.ofMinutes(15));

  /**
   * ADR 0028: one limit for every kind in this increment; the switch keeps that choice explicit.
   */
  @Override
  public CredentialAttemptPolicy policyFor(CredentialKind kind) {
    return switch (kind) {
      case ACCOUNT_LOGIN,
          ACCOUNT_PASSWORD_VERIFICATION,
          PROFILE_PIN,
          ACCOUNT_INVITATION_CODE,
          PASSWORD_RESET_CODE,
          PROFILE_MANAGER_INVITATION_CODE,
          DEVICE_PAIRING_CODE ->
          STANDARD_POLICY;
    };
  }
}

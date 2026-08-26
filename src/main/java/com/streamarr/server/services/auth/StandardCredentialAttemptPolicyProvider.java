package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.CredentialAttemptPolicy;
import com.streamarr.server.domain.auth.CredentialKind;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class StandardCredentialAttemptPolicyProvider implements CredentialAttemptPolicyProvider {

  private static final CredentialAttemptPolicy STANDARD_POLICY =
      new CredentialAttemptPolicy.Limited(5, Duration.ofMinutes(15), Duration.ofMinutes(15));

  @Override
  public CredentialAttemptPolicy policyFor(CredentialKind kind) {
    return STANDARD_POLICY;
  }
}

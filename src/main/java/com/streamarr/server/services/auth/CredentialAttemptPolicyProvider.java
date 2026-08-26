package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.CredentialAttemptPolicy;
import com.streamarr.server.domain.auth.CredentialKind;

public interface CredentialAttemptPolicyProvider {

  CredentialAttemptPolicy policyFor(CredentialKind kind);
}

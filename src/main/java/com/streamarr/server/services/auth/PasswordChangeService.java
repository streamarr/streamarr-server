package com.streamarr.server.services.auth;

import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.DeviceBoundSessionException;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Password verification and hashing are deliberately slow, CPU-bound work; inside a transaction
 * they would pin a pooled connection for their whole duration — the Hikari-exhaustion failure
 * class. Only the short completion step transacts: revoke old sessions, mint replacements (ADR
 * 0016).
 */
@Service
@RequiredArgsConstructor
public class PasswordChangeService {

  private final UserAccountRepository userAccountRepository;
  private final PasswordChangeCompletionService completionService;
  private final AccountPasswordVerifier passwordVerifier;
  private final PasswordEncoder passwordEncoder;

  public PasswordChangeResult changePassword(
      AuthenticatedIdentity identity, ChangePasswordCommand command) {
    if (identity.deviceBound()) {
      throw new DeviceBoundSessionException();
    }

    var account =
        userAccountRepository
            .findById(identity.accountId())
            .orElseThrow(AuthenticationRequiredException::new);
    passwordVerifier.verify(account, command.currentPassword());
    var newPasswordHash = passwordEncoder.encode(command.newPassword());

    return completionService.complete(
        PasswordChangeCompletionCommand.builder()
            .accountId(account.getId())
            .sessionId(identity.authSessionId())
            .expectedPasswordHash(account.getPasswordHash())
            .newPasswordHash(newPasswordHash)
            .build());
  }
}

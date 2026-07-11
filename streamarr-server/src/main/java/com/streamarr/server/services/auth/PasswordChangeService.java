package com.streamarr.server.services.auth;

import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * ADR 0016: raw passwords are consumed outside a transaction, then a short transactional completion
 * revokes every old session and creates isolated replacement credentials for the caller.
 */
@Service
@RequiredArgsConstructor
public class PasswordChangeService {

  private final UserAccountRepository userAccountRepository;
  private final PasswordChangeCompletionService completionService;
  private final PasswordEncoder passwordEncoder;

  public PasswordChangeResult changePassword(ChangePasswordCommand command) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "Password changes require a non-transactional caller");
    }
    var credential =
        userAccountRepository
            .findCredentialById(command.accountId())
            .orElseThrow(AuthenticationRequiredException::new);

    if (!passwordEncoder.matches(command.currentPassword(), credential.passwordHash())) {
      throw new InvalidCredentialsException();
    }
    var newPasswordHash = passwordEncoder.encode(command.newPassword());

    return completionService.complete(
        PasswordChangeCompletionCommand.builder()
            .accountId(credential.accountId())
            .sessionId(command.sessionId())
            .expectedPasswordHash(credential.passwordHash())
            .newPasswordHash(newPasswordHash)
            .build());
  }
}

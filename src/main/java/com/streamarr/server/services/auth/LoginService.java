package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginService {

  private final UserAccountRepository userAccountRepository;
  private final LoginCompletionService loginCompletionService;
  private final PasswordEncoder passwordEncoder;
  private final CredentialAttemptGate credentialAttempts;
  private final PasswordTimingEqualizer timingEqualizer;

  // Deliberately not @Transactional: a method-level transaction would pin a pooled connection
  // across Argon2 work. The gate commits the session and journal outcome in one short transaction.
  public LoginResult login(LoginCommand command) {
    var email = lookupEmail(command.email());
    var account = userAccountRepository.findByEmailIgnoreCase(email).orElse(null);
    // The completion transaction rechecks the stored hash under its row lock. A refusal rolls
    // back the transaction before the gate journals the failed attempt.
    return credentialAttempts.attempt(
        loginTarget(command, account),
        () -> {
          if (account == null) {
            timingEqualizer.burn(command.password());
            throw new InvalidCredentialsException();
          }

          if (!credentialsValid(account, command.password())) {
            throw new InvalidCredentialsException();
          }

          return LoginCompletionCommand.builder()
              .accountId(account.getId())
              .expectedPasswordHash(account.getPasswordHash())
              .upgradedPasswordHash(upgradedPasswordHash(account, command.password()))
              .deviceName(command.deviceName())
              .build();
        },
        loginCompletionService::complete);
  }

  private static CredentialAttemptTarget loginTarget(LoginCommand command, UserAccount account) {
    return CredentialAttemptTarget.builder()
        .kind(CredentialKind.ACCOUNT_LOGIN)
        .accountId(account == null ? null : account.getId())
        .ipAddress(command.ipAddress())
        .build();
  }

  /**
   * A blank or malformed address is looked up as typed so it fails exactly like an unknown one:
   * throttled, burned, InvalidCredentials — never a distinguishable early rejection.
   */
  private static String lookupEmail(String typed) {
    return switch (EmailAddressValidator.validate(typed)) {
      case EmailAddressValidator.Valid(var address) -> address;
      case EmailAddressValidator.Blank _, EmailAddressValidator.Malformed _ -> typed;
    };
  }

  private boolean credentialsValid(UserAccount account, String password) {
    if (!account.isEnabled()) {
      timingEqualizer.burn(password);
      return false;
    }

    var passwordHash = account.getPasswordHash();
    if (passwordHash == null || passwordHash.isEmpty()) {
      log.error("Stored password hash for account {} is unreadable.", account.getId());
      timingEqualizer.burn(password);
      return false;
    }

    try {
      return passwordEncoder.matches(password, passwordHash);
    } catch (IllegalArgumentException e) {
      // An unreadable stored hash must fail like a wrong password, not escape as a raw error
      // that marks the account's broken state.
      log.error("Stored password hash for account {} is unreadable.", account.getId(), e);
      timingEqualizer.burn(password);
      return false;
    }
  }

  private Optional<String> upgradedPasswordHash(UserAccount account, String rawPassword) {
    if (!passwordEncoder.upgradeEncoding(account.getPasswordHash())) {
      return Optional.empty();
    }

    return Optional.of(passwordEncoder.encode(rawPassword));
  }
}

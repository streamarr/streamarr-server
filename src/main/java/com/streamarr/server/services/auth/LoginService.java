package com.streamarr.server.services.auth;

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
  private final LoginThrottle throttle;
  private final PasswordTimingEqualizer timingEqualizer;

  // Deliberately not @Transactional: a method-level transaction would pin a pooled connection
  // across the Argon2 work (the documented Hikari-exhaustion pattern). LoginCompletionService owns
  // the short transaction that begins only after all password work has finished.
  public LoginResult login(LoginCommand command) {
    // Reserve the slot before any password work — recording failures after hashing is a
    // check-then-act race that lets a concurrent burst overrun the budget.
    throttle.registerAttempt(command.email(), command.source());

    var account = userAccountRepository.findByEmailIgnoreCase(command.email()).orElse(null);
    if (account == null) {
      timingEqualizer.burn(command.password());
      throw new InvalidCredentialsException();
    }

    if (!credentialsValid(account, command.password())) {
      throw new InvalidCredentialsException();
    }

    var result =
        loginCompletionService.complete(
            LoginCompletionCommand.builder()
                .accountId(account.getId())
                .expectedPasswordHash(account.getPasswordHash())
                .upgradedPasswordHash(upgradedPasswordHash(account, command.password()))
                .deviceName(command.deviceName())
                .build());

    throttle.reset(command.email(), command.source());
    return result;
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

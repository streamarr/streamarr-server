package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.ServerAdministrationDeniedException;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ServerAdminAuthorizer {

  private final UserAccountRepository accountRepository;
  private final PasswordEncoder passwordEncoder;
  private final CredentialGuessThrottle throttle;

  public UserAccount requireFreshAuthority(UUID accountId, String password) {
    var account =
        accountRepository.findById(accountId).orElseThrow(ServerAdministrationDeniedException::new);
    if (!account.isEnabled() || account.getAccountRole() != AccountRole.ADMIN) {
      throw new ServerAdministrationDeniedException();
    }
    throttle.registerServerAdminPasswordAttempt(accountId);
    if (!passwordMatches(account, password)) {
      throw new InvalidCredentialsException();
    }
    throttle.resetServerAdminPasswordAttempts(accountId);
    return account;
  }

  private boolean passwordMatches(UserAccount account, String password) {
    try {
      return passwordEncoder.matches(password, account.getPasswordHash());
    } catch (IllegalArgumentException exception) {
      log.error(
          "Stored password hash for administrator account {} is unreadable.",
          account.getId(),
          exception);
      return false;
    }
  }
}

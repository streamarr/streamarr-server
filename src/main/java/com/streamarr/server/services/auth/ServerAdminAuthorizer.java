package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.ServerAdministrationDeniedException;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ServerAdminAuthorizer {

  private final UserAccountRepository accountRepository;
  private final PasswordEncoder passwordEncoder;

  public UserAccount requireFreshAuthority(UUID accountId, String password) {
    var account =
        accountRepository.findById(accountId).orElseThrow(ServerAdministrationDeniedException::new);
    if (!account.isEnabled() || account.getAccountRole() != AccountRole.ADMIN) {
      throw new ServerAdministrationDeniedException();
    }
    if (!passwordEncoder.matches(password, account.getPasswordHash())) {
      throw new InvalidCredentialsException();
    }
    return account;
  }
}

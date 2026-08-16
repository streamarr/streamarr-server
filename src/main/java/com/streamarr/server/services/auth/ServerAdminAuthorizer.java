package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.exceptions.ServerAdministrationDeniedException;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ServerAdminAuthorizer {

  private final UserAccountRepository accountRepository;
  private final AccountPasswordVerifier passwordVerifier;

  public PasswordReauthentication prepare(UUID accountId, String password) {
    var account =
        accountRepository.findById(accountId).orElseThrow(ServerAdministrationDeniedException::new);
    if (!account.isEnabled() || account.getAccountRole() != AccountRole.ADMIN) {
      throw new ServerAdministrationDeniedException();
    }
    return passwordVerifier.verify(account, password);
  }

  public void requireFreshAuthority(PasswordReauthentication reauthentication) {
    if (!accountRepository.lockIfServerAdmin(reauthentication.accountId())) {
      throw new ServerAdministrationDeniedException();
    }
  }
}

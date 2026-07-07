package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.Optional;

public class FakeUserAccountRepository extends FakeJpaRepository<UserAccount>
    implements UserAccountRepository {

  @Override
  public Optional<UserAccount> findByEmailIgnoreCase(String email) {
    return database.values().stream()
        .filter(account -> account.getEmail().equalsIgnoreCase(email))
        .findFirst();
  }
}

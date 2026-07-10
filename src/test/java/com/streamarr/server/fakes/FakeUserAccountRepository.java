package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.sql.SQLException;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

public class FakeUserAccountRepository extends FakeJpaRepository<UserAccount>
    implements UserAccountRepository {

  @Override
  public boolean lockIfCredentialsUnchanged(java.util.UUID accountId, String expectedPasswordHash) {
    return findById(accountId)
        .filter(UserAccount::isEnabled)
        .filter(account -> account.getPasswordHash().equals(expectedPasswordHash))
        .isPresent();
  }

  @Override
  public <S extends UserAccount> S save(S entity) {
    // Mirrors uq_user_account_email on lower(email).
    var duplicateEmail =
        database.values().stream()
            .anyMatch(
                account ->
                    !account.getId().equals(entity.getId())
                        && account.getEmail().equalsIgnoreCase(entity.getEmail()));

    if (duplicateEmail) {
      // Mirrors Spring's Hibernate translation: the constraint name rides the cause chain.
      var message = "duplicate key value violates unique constraint \"uq_user_account_email\"";
      throw new DataIntegrityViolationException(
          message,
          new ConstraintViolationException(
              message, new SQLException(message, "23505"), "uq_user_account_email"));
    }

    return super.save(entity);
  }

  @Override
  public Optional<UserAccount> findByEmailIgnoreCase(String email) {
    return database.values().stream()
        .filter(account -> account.getEmail().equalsIgnoreCase(email))
        .findFirst();
  }
}

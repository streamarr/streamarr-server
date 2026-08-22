package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.AccountAuthorityFacts;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

public class FakeUserAccountRepository extends FakeJpaRepository<UserAccount>
    implements UserAccountRepository {

  private final FakeProfileHouseholdShareRepository shares;

  public FakeUserAccountRepository() {
    this(new FakeProfileHouseholdShareRepository());
  }

  /** Pair with the share fake so "may use a Household" follows the Personal Profile's shares. */
  public FakeUserAccountRepository(FakeProfileHouseholdShareRepository shares) {
    this.shares = shares;
  }

  @Override
  public Optional<AccountAuthorityFacts> findAuthorityFacts(UUID accountId) {
    return findById(accountId)
        .map(account -> new AccountAuthorityFacts(account.isEnabled(), account.isServerAdmin()));
  }

  @Override
  public List<UserAccount> findByHouseholdId(UUID householdId) {
    return database.values().stream()
        .filter(account -> householdId.equals(account.getHouseholdId()))
        .toList();
  }

  @Override
  public boolean mayUseHousehold(UUID accountId, UUID householdId) {
    return findById(accountId)
        .map(
            account ->
                householdId.equals(account.getHouseholdId())
                    || shares.isActivelyShared(account.getPersonalProfileId(), householdId))
        .orElse(false);
  }

  @Override
  public List<UUID> findUsableHouseholdIds(UUID accountId) {
    return findById(accountId)
        .map(
            account -> {
              var ids = new ArrayList<UUID>();
              ids.add(account.getHouseholdId());
              shares
                  .findByProfileIdAndStatus(
                      account.getPersonalProfileId(), ProfileShareStatus.ACTIVE)
                  .stream()
                  .map(share -> share.getHouseholdId())
                  .filter(id -> !id.equals(account.getHouseholdId()))
                  .distinct()
                  .sorted()
                  .forEach(ids::add);
              return List.copyOf(ids);
            })
        .orElse(List.of());
  }

  @Override
  public boolean lockIfCredentialsUnchanged(UUID accountId, String expectedPasswordHash) {
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

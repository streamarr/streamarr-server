package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.AccountAuthorityFacts;
import com.streamarr.server.domain.auth.AccountShareFacts;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileManagerEligibility;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.SortValue;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

public class FakeUserAccountRepository extends FakeJpaRepository<UserAccount>
    implements UserAccountRepository {

  private final FakeProfileHouseholdShareRepository shares;
  private final Predicate<UUID> unrestrictedPersonalProfile;

  public FakeUserAccountRepository() {
    this(new FakeProfileHouseholdShareRepository(), _ -> false);
  }

  /** Pair with the share fake so "may use a Household" follows the Personal Profile's shares. */
  public FakeUserAccountRepository(FakeProfileHouseholdShareRepository shares) {
    this(shares, _ -> false);
  }

  /** Pair with the Profile fake when tests exercise Profile-manager eligibility. */
  public FakeUserAccountRepository(FakeProfileRepository profiles) {
    this(
        profiles.shares(),
        profileId ->
            profiles.findById(profileId).filter(profile -> !profile.isRestricted()).isPresent());
  }

  private FakeUserAccountRepository(
      FakeProfileHouseholdShareRepository shares, Predicate<UUID> unrestrictedPersonalProfile) {
    this.shares = shares;
    this.unrestrictedPersonalProfile = unrestrictedPersonalProfile;
  }

  @Override
  public void refresh(UserAccount account) {
    // The fake stores and returns the same mutable instance.
  }

  @Override
  public Optional<AccountAuthorityFacts> findAuthorityFacts(UUID accountId) {
    return findById(accountId)
        .map(account -> new AccountAuthorityFacts(account.isEnabled(), account.isServerAdmin()));
  }

  @Override
  public Optional<AccountShareFacts> findShareFacts(UUID accountId) {
    return findById(accountId)
        .map(
            account ->
                new AccountShareFacts(
                    account.getHouseholdId(),
                    account.getHouseholdRole(),
                    account.getPersonalProfileId()));
  }

  @Override
  public Map<UUID, List<Row<UserAccount>>> rowsBeyond(Map<UUID, Seek> seeksByHousehold) {
    var rows = new LinkedHashMap<UUID, List<Row<UserAccount>>>();
    seeksByHousehold.forEach(
        (householdId, seek) ->
            rows.put(
                householdId,
                InMemoryKeyset.rowsBeyond(
                    database.values().stream()
                        .filter(account -> householdId.equals(account.getHouseholdId()))
                        .map(FakeUserAccountRepository::rowOf)
                        .toList(),
                    seek)));
    return rows;
  }

  private static Row<UserAccount> rowOf(UserAccount account) {
    return new Row<>(
        account,
        new Position(
            new SortValue.OfText(account.getDisplayName().toLowerCase(Locale.ROOT)),
            account.getId()));
  }

  @Override
  public Optional<UserAccount> findByPersonalProfileId(UUID profileId) {
    return database.values().stream()
        .filter(account -> profileId.equals(account.getPersonalProfileId()))
        .findFirst();
  }

  @Override
  public List<UserAccount> findByHouseholdId(UUID householdId) {
    return database.values().stream()
        .filter(account -> householdId.equals(account.getHouseholdId()))
        .toList();
  }

  @Override
  public boolean tryGrantServerAdmin(UUID accountId) {
    return transition(accountId, account -> !account.isServerAdmin(), a -> a.setServerAdmin(true));
  }

  @Override
  public boolean tryRevokeServerAdmin(UUID accountId) {
    return transition(accountId, UserAccount::isServerAdmin, a -> a.setServerAdmin(false));
  }

  @Override
  public boolean tryPromoteToHouseholdAdmin(UUID accountId) {
    return transition(
        accountId,
        account -> account.getHouseholdRole() != HouseholdRole.ADMIN,
        account -> account.setHouseholdRole(HouseholdRole.ADMIN));
  }

  @Override
  public boolean tryDemoteToHouseholdMember(UUID accountId) {
    return transition(
        accountId,
        account -> account.getHouseholdRole() != HouseholdRole.MEMBER,
        account -> account.setHouseholdRole(HouseholdRole.MEMBER));
  }

  @Override
  public boolean tryDisable(UUID accountId) {
    return transition(accountId, UserAccount::isEnabled, account -> account.setEnabled(false));
  }

  @Override
  public boolean tryEnable(UUID accountId) {
    return transition(accountId, account -> !account.isEnabled(), a -> a.setEnabled(true));
  }

  @Override
  public boolean tryRename(UUID accountId, String displayName) {
    return transition(accountId, _ -> true, account -> account.setDisplayName(displayName));
  }

  @Override
  public boolean trySetPasswordHash(UUID accountId, String passwordHash) {
    return transition(accountId, _ -> true, account -> account.setPasswordHash(passwordHash));
  }

  private boolean transition(
      UUID accountId, Predicate<UserAccount> transitionable, Consumer<UserAccount> change) {
    var account = findById(accountId).filter(transitionable);
    account.ifPresent(change);
    return account.isPresent();
  }

  @Override
  public Optional<UserAccount> findByIdAndReloadFromDatabase(UUID accountId) {
    return findById(accountId);
  }

  @Override
  public boolean tryTransfer(
      UUID accountId, UUID expectedHouseholdId, UUID destinationHouseholdId, HouseholdRole role) {
    return transition(
        accountId,
        account -> expectedHouseholdId.equals(account.getHouseholdId()),
        account -> {
          account.setHouseholdId(destinationHouseholdId);
          account.setHouseholdRole(role);
        });
  }

  @Override
  public boolean tryDelete(UUID accountId, UUID expectedHouseholdId) {
    var account = findById(accountId).filter(a -> expectedHouseholdId.equals(a.getHouseholdId()));
    account.ifPresent(a -> deleteById(a.getId()));
    return account.isPresent();
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
  public Set<UUID> lockByIds(Set<UUID> accountIds, Duration timeout) {
    return accountIds.stream().filter(this::existsById).collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public boolean lockIfCredentialsUnchanged(UUID accountId, String expectedPasswordHash) {
    return findById(accountId)
        .filter(UserAccount::isEnabled)
        .filter(account -> account.getPasswordHash().equals(expectedPasswordHash))
        .isPresent();
  }

  @Override
  public boolean tryLockEnabledServerAdmin(UUID accountId) {
    return findById(accountId)
        .filter(UserAccount::isEnabled)
        .filter(UserAccount::isServerAdmin)
        .isPresent();
  }

  @Override
  public Optional<HouseholdRole> roleForNewAccount(UUID householdId, HouseholdRole requestedRole) {
    return Optional.of(
        findByHouseholdId(householdId).isEmpty() ? HouseholdRole.ADMIN : requestedRole);
  }

  @Override
  public boolean isEligibleProfileManager(
      UUID accountId, UUID householdId, ProfileManagerEligibility eligibility) {
    return findById(accountId)
        .filter(account -> householdId.equals(account.getHouseholdId()))
        .filter(account -> unrestrictedPersonalProfile.test(account.getPersonalProfileId()))
        .filter(account -> holdsRoleFor(account, eligibility))
        .isPresent();
  }

  private static boolean holdsRoleFor(UserAccount account, ProfileManagerEligibility eligibility) {
    return eligibility == ProfileManagerEligibility.HOUSEHOLD_MEMBER
        || account.getHouseholdRole() == HouseholdRole.ADMIN;
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

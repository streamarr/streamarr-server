package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FakeProfileManagerRepository extends FakeJpaRepository<ProfileManager>
    implements ProfileManagerRepository {

  /**
   * Determines whether a profile manager exists for the specified account and profile.
   *
   * @param accountId  the account identifier
   * @param profileId  the profile identifier
   * @return           {@code true} if a matching profile manager exists, {@code false} otherwise
   */
  @Override
  public boolean existsByAccountIdAndProfileId(UUID accountId, UUID profileId) {
    return database.values().stream()
        .anyMatch(
            manager ->
                accountId.equals(manager.getAccountId())
                    && profileId.equals(manager.getProfileId()));
  }

  /**
   * Adds a profile manager association when the account and profile pair is not already present.
   *
   * @param accountId the account identifier
   * @param profileId the profile identifier
   * @return {@code true} if the association was added, {@code false} if it already exists
   */
  @Override
  public synchronized boolean insertIfAbsent(UUID accountId, UUID profileId) {
    if (existsByAccountIdAndProfileId(accountId, profileId)) {
      return false;
    }
    save(ProfileManager.builder().accountId(accountId).profileId(profileId).build());
    return true;
  }

  /**
   * Finds all profile managers associated with the specified profile.
   *
   * @param profileId the profile identifier
   * @return the profile managers associated with the profile
   */
  @Override
  public List<ProfileManager> findByProfileId(UUID profileId) {
    return database.values().stream()
        .filter(manager -> profileId.equals(manager.getProfileId()))
        .toList();
  }

  /**
   * Finds all profile managers associated with an account.
   *
   * @param accountId the account identifier
   * @return the profile managers associated with the account
   */
  @Override
  public List<ProfileManager> findByAccountId(UUID accountId) {
    return database.values().stream()
        .filter(manager -> accountId.equals(manager.getAccountId()))
        .toList();
  }

  /**
   * Finds the first profile manager associated with the specified account and profile.
   *
   * @param accountId the account identifier
   * @param profileId the profile identifier
   * @return the matching profile manager, or an empty result if none exists
   */
  @Override
  public Optional<ProfileManager> findByAccountIdAndProfileId(UUID accountId, UUID profileId) {
    return database.values().stream()
        .filter(manager -> accountId.equals(manager.getAccountId()))
        .filter(manager -> profileId.equals(manager.getProfileId()))
        .findFirst();
  }

  /**
   * Counts the managers associated with a profile.
   *
   * @param profileId the profile identifier
   * @return the number of managers associated with the profile
   */
  @Override
  public long countByProfileId(UUID profileId) {
    return findByProfileId(profileId).size();
  }
}

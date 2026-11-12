package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class FakeProfileManagerRepository extends FakeJpaRepository<ProfileManager>
    implements ProfileManagerRepository {

  @Override
  public boolean existsByAccountIdAndProfileId(UUID accountId, UUID profileId) {
    return database.values().stream()
        .anyMatch(
            manager ->
                manager.getAccountId().equals(accountId)
                    && manager.getProfileId().equals(profileId));
  }

  @Override
  public List<ProfileManager> findByAccountIdAndProfileIdIn(
      UUID accountId, Collection<UUID> profileIds) {
    return database.values().stream()
        .filter(manager -> manager.getAccountId().equals(accountId))
        .filter(manager -> profileIds.contains(manager.getProfileId()))
        .toList();
  }

  @Override
  public boolean lockDirectManagement(UUID accountId, UUID profileId) {
    return existsByAccountIdAndProfileId(accountId, profileId);
  }

  @Override
  public Set<UUID> lockManagerIds(UUID profileId) {
    throw new UnsupportedOperationException(
        "No unit test locks a Profile's managers yet: give the fake a contract test against the real repository"
            + " before using it");
  }

  @Override
  public boolean tryGrantDirectManagement(UUID accountId, UUID profileId) {
    if (existsByAccountIdAndProfileId(accountId, profileId)) {
      return false;
    }

    save(ProfileManager.builder().accountId(accountId).profileId(profileId).build());
    return true;
  }

  @Override
  public boolean tryRevokeDirectManagement(UUID accountId, UUID profileId) {
    var grant =
        database.values().stream()
            .filter(manager -> manager.getAccountId().equals(accountId))
            .filter(manager -> manager.getProfileId().equals(profileId))
            .findFirst();
    grant.ifPresent(found -> database.remove(found.getId()));
    return grant.isPresent();
  }

  @Override
  public List<ProfileManager> findByProfileId(UUID profileId) {
    return database.values().stream()
        .filter(manager -> manager.getProfileId().equals(profileId))
        .toList();
  }
}

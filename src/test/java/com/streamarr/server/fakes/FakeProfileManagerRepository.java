package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import java.util.List;
import java.util.Optional;
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
  public Optional<ProfileManager> findByAccountIdAndProfileId(UUID accountId, UUID profileId) {
    return database.values().stream()
        .filter(manager -> manager.getAccountId().equals(accountId))
        .filter(manager -> manager.getProfileId().equals(profileId))
        .findFirst();
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

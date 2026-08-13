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
                accountId.equals(manager.getAccountId())
                    && profileId.equals(manager.getProfileId()));
  }

  @Override
  public List<ProfileManager> findByProfileId(UUID profileId) {
    return database.values().stream()
        .filter(manager -> profileId.equals(manager.getProfileId()))
        .toList();
  }

  @Override
  public List<ProfileManager> findByAccountId(UUID accountId) {
    return database.values().stream()
        .filter(manager -> accountId.equals(manager.getAccountId()))
        .toList();
  }

  @Override
  public Optional<ProfileManager> findByAccountIdAndProfileId(UUID accountId, UUID profileId) {
    return database.values().stream()
        .filter(manager -> accountId.equals(manager.getAccountId()))
        .filter(manager -> profileId.equals(manager.getProfileId()))
        .findFirst();
  }

  @Override
  public long countByProfileId(UUID profileId) {
    return findByProfileId(profileId).size();
  }
}

package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.repositories.auth.ProfileRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Pair with a {@link FakeProfileHouseholdShareRepository} so availability follows the shares. */
public class FakeProfileRepository extends FakeJpaRepository<Profile> implements ProfileRepository {

  private final FakeProfileHouseholdShareRepository shares;

  public FakeProfileRepository() {
    this(new FakeProfileHouseholdShareRepository());
  }

  public FakeProfileRepository(FakeProfileHouseholdShareRepository shares) {
    this.shares = shares;
  }

  public FakeProfileHouseholdShareRepository shares() {
    return shares;
  }

  @Override
  public List<Profile> findByHouseholdId(UUID householdId) {
    return database.values().stream()
        .filter(profile -> householdId.equals(profile.getHouseholdId()))
        .toList();
  }

  @Override
  public List<Profile> findAvailableInHousehold(UUID householdId) {
    var available =
        shares.findByHouseholdIdAndStatus(householdId, ProfileShareStatus.ACTIVE).stream()
            .map(share -> share.getProfileId())
            .toList();
    return database.values().stream()
        .filter(profile -> available.contains(profile.getId()))
        .sorted(Comparator.comparing(Profile::getName).thenComparing(Profile::getId))
        .toList();
  }
}

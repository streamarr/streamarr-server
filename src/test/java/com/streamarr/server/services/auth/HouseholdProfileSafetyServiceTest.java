package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Household Profile Safety Service Tests")
class HouseholdProfileSafetyServiceTest {

  private final TrackingShareRepository shareRepository = new TrackingShareRepository();
  private final FakeProfileRepository profileRepository = new FakeProfileRepository();
  private final HouseholdProfileSafetyService service =
      new HouseholdProfileSafetyService(shareRepository, profileRepository);

  @Test
  @DisplayName("Should batch household safety reads when policy change affects several homes")
  void shouldBatchHouseholdSafetyReadsWhenPolicyChangeAffectsSeveralHomes() {
    var profile =
        profileRepository.save(
            Profile.builder()
                .name("Protected Adult")
                .kind(ProfileKind.ADULT)
                .pinHash("encoded-pin")
                .build());
    share(profile, UUID.randomUUID());
    share(profile, UUID.randomUUID());

    assertThatCode(() -> service.validatePolicyChange(profile)).doesNotThrowAnyException();

    assertThat(shareRepository.individualHouseholdLookups).isZero();
    assertThat(shareRepository.batchHouseholdLookups).isOne();
  }

  private void share(Profile profile, UUID householdId) {
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(householdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
  }

  private static final class TrackingShareRepository extends FakeProfileHouseholdShareRepository {

    private int individualHouseholdLookups;
    private int batchHouseholdLookups;

    @Override
    public List<ProfileHouseholdShare> findByHouseholdIdAndStatus(
        UUID householdId, ProfileShareStatus status) {
      individualHouseholdLookups++;
      return super.findByHouseholdIdAndStatus(householdId, status);
    }

    @Override
    public List<ProfileHouseholdShare> findByHouseholdIdInAndStatus(
        Collection<UUID> householdIds, ProfileShareStatus status) {
      batchHouseholdLookups++;
      return super.findByHouseholdIdInAndStatus(householdIds, status);
    }
  }
}

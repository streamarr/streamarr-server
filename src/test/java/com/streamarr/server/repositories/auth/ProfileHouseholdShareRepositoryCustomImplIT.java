package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.support.security.WithAccountContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.AuditorAware;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Profile Household Share Repository Custom Implementation Integration Tests")
@WithAccountContext
@Transactional
class ProfileHouseholdShareRepositoryCustomImplIT extends AbstractIntegrationTest {

  @Autowired private HouseholdRepository householdRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private AuditorAware<UUID> auditorAware;
  @Autowired private TransactionTemplate transactions;

  @Test
  @DisplayName("Should populate audit fields when inserting a structural home share")
  void shouldPopulateAuditFieldsWhenInsertingStructuralHomeShare() {
    var now = Instant.parse("2026-08-21T12:00:00Z");
    var ids =
        transactions.execute(
            _ -> {
              var household =
                  householdRepository.saveAndFlush(
                      HouseholdFixture.defaultHouseholdBuilder().build());
              var profile =
                  profileRepository.saveAndFlush(
                      ProfileFixture.defaultProfileBuilder()
                          .householdId(household.getId())
                          .build());
              shareRepository.upsertStructuralHomeShare(profile.getId(), household.getId(), now);
              return new ProfileHouseholdIds(profile.getId(), household.getId());
            });

    var share =
        shareRepository
            .findByProfileIdAndHouseholdIdAndStatus(
                ids.profileId(), ids.householdId(), ProfileShareStatus.ACTIVE)
            .orElseThrow();
    var expectedAuditor = auditorAware.getCurrentAuditor().orElseThrow();

    assertSoftly(
        softly -> {
          softly.assertThat(share.getCreatedOn()).isEqualTo(now);
          softly.assertThat(share.getLastModifiedOn()).isEqualTo(now);
          softly.assertThat(share.getCreatedBy()).isEqualTo(expectedAuditor);
          softly.assertThat(share.getLastModifiedBy()).isEqualTo(expectedAuditor);
        });
  }

  private record ProfileHouseholdIds(UUID profileId, UUID householdId) {}
}

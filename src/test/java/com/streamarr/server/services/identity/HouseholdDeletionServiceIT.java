package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.identity.HouseholdDeletionService.HouseholdDeletionPreflightDetails;
import com.streamarr.server.services.identity.HouseholdDeletionService.TransferLastAccountAndDeleteHouseholdCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Household Deletion Service Integration Tests")
class HouseholdDeletionServiceIT extends AbstractIntegrationTest {

  @Autowired private HouseholdDeletionService service;
  @Autowired private AdministrationQueryService administration;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileRepository profileRepository;

  @Test
  @DisplayName("Should reject and roll back transfer when the destination Profile name is taken")
  void shouldRejectAndRollBackTransferWhenDestinationProfileNameIsTaken() {
    var admin = authTestSupport.createAdminIdentity();
    var source = authTestSupport.createIdentity();
    try {
      admin.profile().setName("Morgan");
      profileRepository.saveAndFlush(admin.profile());
      source.profile().setName("mOrGaN");
      profileRepository.saveAndFlush(source.profile());
      var identity = authTestSupport.freshIdentityOf(admin);

      var outcome =
          service.transferLastAccountAndDeleteHousehold(
              identity,
              TransferLastAccountAndDeleteHouseholdCommand.builder()
                  .householdId(source.household().getId())
                  .destinationHouseholdId(admin.household().getId())
                  .reason("closing Household")
                  .build());

      assertThat(outcome).isInstanceOf(Outcome.Rejected.class);
      assertThat(service.deletionPreflight(identity, source.household().getId()))
          .get()
          .extracting(HouseholdDeletionPreflightDetails::accountCount)
          .isEqualTo(1);
      assertThat(administration.accountAdministration(identity, source.account().getId()))
          .get()
          .extracting(UserAccount::getHouseholdId)
          .isEqualTo(source.household().getId());
      assertThat(administration.profileAdministration(identity, source.profile().getId()))
          .get()
          .extracting(details -> details.profile().getHouseholdId())
          .isEqualTo(source.household().getId());
    } finally {
      authTestSupport.deleteIdentity(source);
      authTestSupport.deleteIdentity(admin);
    }
  }
}

package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.RefreshResult;
import com.streamarr.server.services.auth.RefreshTokenService;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.SecurityAuditPageRequest;
import com.streamarr.server.services.identity.HouseholdDeletionService.TransferLastAccountAndDeleteHouseholdCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.support.AuthTestSupport;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Tag("IntegrationTest")
@DisplayName("Household Deletion Completion Integration Tests")
@Isolated("Compares shared PostgreSQL audit state")
class HouseholdDeletionCompletionIT extends AbstractIntegrationTest {

  @Autowired private HouseholdDeletionService deletion;
  @Autowired private AdministrationQueryService administration;
  @Autowired private ProfileSharingService sharing;
  @Autowired private RefreshTokenService refreshTokens;
  @Autowired private AuthTestSupport authTestSupport;
  @MockitoSpyBean private ProfileRepository profiles;
  @MockitoSpyBean private UserAccountRepository accounts;

  private AuthTestSupport.TestIdentity admin;
  private AuthTestSupport.TestIdentity source;

  @BeforeEach
  void setUp() {
    admin = authTestSupport.createAdminIdentity();
    source = authTestSupport.createIdentity();
  }

  @AfterEach
  void tearDown() {
    authTestSupport.deleteIdentity(source);
    authTestSupport.deleteIdentity(admin);
  }

  @Test
  @DisplayName(
      "Should roll back Household deletion when the preserved Personal Profile cannot move")
  void shouldRollBackHouseholdDeletionWhenPreservedPersonalProfileCannotMove() {
    var identity = authTestSupport.freshIdentityOf(admin);
    var auditBefore = deletion.securityAuditEvents(identity, auditPage());
    doReturn(false)
        .when(profiles)
        .tryRehome(source.profile().getId(), source.household().getId(), admin.household().getId());

    assertThatThrownBy(
            () ->
                deletion.deleteLastAccountAndHouseholdPreservingPersonalProfile(
                    identity,
                    DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand.builder()
                        .householdId(source.household().getId())
                        .destinationHouseholdId(admin.household().getId())
                        .replacementManagerAccountId(admin.account().getId())
                        .reason("closing Household")
                        .build()))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("The Personal Profile could not move to the requested Household.");

    assertSourceIdentityIntact();
    assertThat(deletion.securityAuditEvents(identity, auditPage())).isEqualTo(auditBefore);
    assertThat(refreshTokens.redeem(source.rawRefreshToken()))
        .isInstanceOf(RefreshResult.Rotated.class);
  }

  @Test
  @DisplayName(
      "Should roll back Household deletion when the transferred Personal Profile cannot move")
  void shouldRollBackHouseholdDeletionWhenTransferredPersonalProfileCannotMove() {
    doReturn(false)
        .when(profiles)
        .tryRehome(source.profile().getId(), source.household().getId(), admin.household().getId());

    assertThatThrownBy(
            () ->
                deletion.transferLastAccountAndDeleteHousehold(
                    authTestSupport.freshIdentityOf(admin),
                    TransferLastAccountAndDeleteHouseholdCommand.builder()
                        .householdId(source.household().getId())
                        .destinationHouseholdId(admin.household().getId())
                        .reason("closing Household")
                        .build()))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("The Personal Profile could not move to the requested Household.");

    assertSourceIdentityIntact();
  }

  @Test
  @DisplayName("Should reject Household deletion when the final Account cannot transfer")
  void shouldRejectHouseholdDeletionWhenFinalAccountCannotTransfer() {
    doReturn(false)
        .when(accounts)
        .tryTransfer(
            source.account().getId(),
            source.household().getId(),
            admin.household().getId(),
            HouseholdRole.MEMBER);

    var outcome =
        deletion.transferLastAccountAndDeleteHousehold(
            authTestSupport.freshIdentityOf(admin),
            TransferLastAccountAndDeleteHouseholdCommand.builder()
                .householdId(source.household().getId())
                .destinationHouseholdId(admin.household().getId())
                .reason("closing Household")
                .build());

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new HouseholdDeletionRejections.LastAccountNotFound()));
    assertSourceIdentityIntact();
  }

  @Test
  @DisplayName("Should reject Household deletion when the final Account cannot be deleted")
  void shouldRejectHouseholdDeletionWhenFinalAccountCannotBeDeleted() {
    var identity = authTestSupport.freshIdentityOf(admin);
    var auditBefore = deletion.securityAuditEvents(identity, auditPage());
    doReturn(false).when(accounts).tryDelete(source.account().getId(), source.household().getId());

    var outcome =
        deletion.deleteLastAccountAndHouseholdPreservingPersonalProfile(
            identity,
            DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand.builder()
                .householdId(source.household().getId())
                .destinationHouseholdId(admin.household().getId())
                .replacementManagerAccountId(admin.account().getId())
                .reason("closing Household")
                .build());

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new HouseholdDeletionRejections.LastAccountNotFound()));
    assertSourceIdentityIntact();
    assertThat(deletion.securityAuditEvents(identity, auditPage())).isEqualTo(auditBefore);
    assertThat(refreshTokens.redeem(source.rawRefreshToken()))
        .isInstanceOf(RefreshResult.Rotated.class);
  }

  private void assertSourceIdentityIntact() {
    var identity = authTestSupport.freshIdentityOf(admin);
    assertThat(administration.householdAdministration(identity, source.household().getId()))
        .isPresent();
    assertThat(administration.accountAdministration(identity, source.account().getId()))
        .get()
        .extracting(UserAccount::getHouseholdId, UserAccount::getPersonalProfileId)
        .containsExactly(source.household().getId(), source.profile().getId());
    assertThat(administration.profileAdministration(identity, source.profile().getId()))
        .get()
        .satisfies(
            details -> {
              assertThat(details.linked()).isTrue();
              assertThat(details.profile().getHouseholdId()).isEqualTo(source.household().getId());
            });
    assertThat(sharing.profileShares(identity, source.profile().getId(), page()).items())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.item().getHouseholdId()).isEqualTo(source.household().getId());
              assertThat(item.item().getStatus()).isEqualTo(ProfileShareStatus.ACTIVE);
              assertThat(item.item().isStructural()).isTrue();
            });
  }

  private static SecurityAuditPageRequest auditPage() {
    return SecurityAuditPageRequest.builder()
        .direction(PaginationDirection.FORWARD)
        .limit(100)
        .build();
  }

  private static KeysetPaginationOptions page() {
    return new KeysetPaginationOptions(
        null,
        PaginationOptions.builder()
            .paginationDirection(PaginationDirection.FORWARD)
            .cursor(Optional.empty())
            .limit(100)
            .build());
  }
}

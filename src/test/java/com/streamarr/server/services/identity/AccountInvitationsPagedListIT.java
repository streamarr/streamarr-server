package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.AccountInvitation.ACCOUNT_INVITATION;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PagedListContract;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Account Invitation administration paging")
class AccountInvitationsPagedListIT
    extends PagedListContract<AccountInvitation, AccountInvitationsPagedListIT.Scope> {

  private static final UUID LOWEST_ID = new UUID(0, 0);
  private static final Instant EARLIEST_WINDOW = Instant.parse("1990-01-01T00:00:00Z");

  @Autowired private AdministrationQueryService administration;
  @Autowired private CredentialIssuanceService credentialIssuance;
  @Autowired private AccountInvitationRepository invitations;
  @Autowired private AuditColumnFixtures auditColumns;
  @Autowired private AuthTestSupport auth;

  /**
   * A ServerAdmin, and a creation time no other test's invitations share: rank 0 is created then,
   * and each higher rank one second earlier, since the list shows the newest first.
   */
  record Scope(
      AuthTestSupport.TestIdentity admin,
      Instant newestCreatedOn,
      List<UUID> arrangedInvitations) {}

  @Override
  protected Scope createScope() {
    var newest =
        EARLIEST_WINDOW.plus(
            ThreadLocalRandom.current().nextLong(1, 10L * 365 * 24 * 60 * 60), ChronoUnit.SECONDS);
    return new Scope(auth.createAdminIdentity(), newest, new ArrayList<>());
  }

  @Override
  protected void deleteScope(Scope scope) {
    invitations.deleteAllById(scope.arrangedInvitations());
    auth.deleteIdentity(scope.admin());
  }

  @Override
  protected UUID add(Scope scope, int rank) {
    var invitation = issue(scope);
    auditColumns.overwriteCreatedOn(
        ACCOUNT_INVITATION,
        ACCOUNT_INVITATION.ID.eq(invitation.getId()),
        scope.newestCreatedOn().minusSeconds(rank));
    scope.arrangedInvitations().add(invitation.getId());
    return invitation.getId();
  }

  // The product keeps every invitation; deleting the row still proves a page continues past it.
  @Override
  protected void remove(Scope scope, UUID item) {
    invitations.deleteById(item);
    invitations.flush();
    scope.arrangedInvitations().remove(item);
  }

  @Override
  protected ListPage<AccountInvitation> page(Scope scope, PageRequest request) {
    return administration.accountInvitations(auth.identityOf(scope.admin()), request);
  }

  @Override
  protected UUID idOf(AccountInvitation invitation) {
    return invitation.getId();
  }

  @Override
  protected SortDirection tieDirection() {
    return SortDirection.DESC;
  }

  @Override
  protected boolean sortValuesCanTie() {
    return true;
  }

  @Override
  protected Optional<Position> positionBeforeArrangedItems(Scope scope) {
    return Optional.of(
        new Position(new SortValue.OfInstant(scope.newestCreatedOn().plusSeconds(1)), LOWEST_ID));
  }

  @Override
  protected Optional<Position> positionAfterArrangedItems(Scope scope) {
    return Optional.of(
        new Position(new SortValue.OfInstant(scope.newestCreatedOn().minusSeconds(10)), LOWEST_ID));
  }

  private AccountInvitation issue(Scope scope) {
    var outcome =
        credentialIssuance.issueAccountInvitation(
            auth.identityOf(scope.admin()),
            IssueInvitationCommand.builder()
                .recipientEmail("invitee-" + UUID.randomUUID() + "@example.com")
                .householdId(scope.admin().household().getId())
                .householdRole(HouseholdRole.MEMBER)
                .profileName("Invitee")
                .profileKind(ProfileKind.ADULT)
                .build());
    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    return ((Outcome.Accepted<CredentialIssuanceService.IssuedInvitation, ?>) outcome)
        .result()
        .invitation();
  }
}

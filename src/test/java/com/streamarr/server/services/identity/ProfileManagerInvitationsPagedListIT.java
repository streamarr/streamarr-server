package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.ProfileManagerInvitation.PROFILE_MANAGER_INVITATION;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PagedListContract;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Profile manager invitation paging")
class ProfileManagerInvitationsPagedListIT
    extends PagedListContract<
        ProfileManagerInvitation, ProfileManagerInvitationsPagedListIT.Scope> {

  private static final Instant NEWEST = Instant.parse("2026-10-20T12:00:00Z");

  @Autowired private ProfileManagerAdministrationService managerAdministration;
  @Autowired private ProfileManagerInvitationRepository invitations;
  @Autowired private AuditColumnFixtures auditColumns;
  @Autowired private AuthTestSupport auth;

  /**
   * A ServerAdmin inviting managers for its Personal Profile, and the Accounts it invited: one
   * Profile has at most one pending invitation per recipient.
   */
  record Scope(AuthTestSupport.TestIdentity inviter, List<AuthTestSupport.TestIdentity> invited) {}

  @Override
  protected Scope createScope() {
    return new Scope(auth.createAdminIdentity(), new ArrayList<>());
  }

  @Override
  protected void deleteScope(Scope scope) {
    scope.invited().forEach(auth::deleteIdentity);
    auth.deleteIdentity(scope.inviter());
  }

  @Override
  protected UUID add(Scope scope, int rank) {
    var recipient = auth.createIdentity();
    scope.invited().add(recipient);
    var inviter = scope.inviter();
    var invitation =
        invitations.saveAndFlush(
            ProfileManagerInvitation.builder()
                .profileId(inviter.profile().getId())
                .profileName(inviter.profile().getName())
                .inviterAccountId(inviter.account().getId())
                .inviterDisplayName(inviter.account().getDisplayName())
                .recipientAccountId(recipient.account().getId())
                .recipientEmail(recipient.account().getEmail())
                .status(ProfileManagerInvitationStatus.PENDING)
                .expiresAt(Instant.now().plusSeconds(3600))
                .publicId(UUID.randomUUID().toString())
                .secretDigest(new byte[] {1})
                .build());
    auditColumns.overwriteCreatedOn(
        PROFILE_MANAGER_INVITATION,
        PROFILE_MANAGER_INVITATION.ID.eq(invitation.getId()),
        NEWEST.minusSeconds(rank));
    return invitation.getId();
  }

  @Override
  protected void remove(Scope scope, UUID item) {
    var outcome =
        managerAdministration.cancelManagerInvitation(auth.freshIdentityOf(scope.inviter()), item);
    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
  }

  @Override
  protected ListPage<ProfileManagerInvitation> page(Scope scope, PageRequest request) {
    return managerAdministration.pendingManagerInvitationsForProfile(
        auth.identityOf(scope.inviter()), scope.inviter().profile().getId(), request);
  }

  @Override
  protected UUID idOf(ProfileManagerInvitation invitation) {
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
    return Optional.empty();
  }

  @Override
  protected Optional<Position> positionAfterArrangedItems(Scope scope) {
    return Optional.empty();
  }
}

package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.ProfileManagerInvitation.PROFILE_MANAGER_INVITATION;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
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
@DisplayName("Received manager invitation paging")
class ReceivedManagerInvitationsPagedListIT
    extends PagedListContract<
        ProfileManagerInvitation, ReceivedManagerInvitationsPagedListIT.Scope> {

  private static final Instant NEWEST = Instant.parse("2026-10-20T12:00:00Z");

  @Autowired private ProfileManagerAdministrationService managerAdministration;
  @Autowired private ProfileManagerInvitationRepository invitations;
  @Autowired private AuditColumnFixtures auditColumns;
  @Autowired private AuthTestSupport auth;

  /**
   * An Account reading the invitations it received, and the Accounts that invited it to manage
   * their Personal Profiles.
   */
  record Scope(
      AuthTestSupport.TestIdentity recipient, List<AuthTestSupport.TestIdentity> inviters) {}

  @Override
  protected Scope createScope() {
    return new Scope(auth.createIdentity(), new ArrayList<>());
  }

  @Override
  protected void deleteScope(Scope scope) {
    auth.deleteIdentity(scope.recipient());
    scope.inviters().forEach(auth::deleteIdentity);
  }

  @Override
  protected UUID add(Scope scope, int rank) {
    var inviter = auth.createIdentity();
    scope.inviters().add(inviter);
    var profile = inviter.profile();
    var invitation =
        invitations.saveAndFlush(
            ProfileManagerInvitation.builder()
                .profileId(profile.getId())
                .profileName(profile.getName())
                .inviterAccountId(inviter.account().getId())
                .inviterDisplayName(inviter.account().getDisplayName())
                .recipientAccountId(scope.recipient().account().getId())
                .recipientEmail(scope.recipient().account().getEmail())
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

  // Declining needs the invitation's secret code; deleting the row still proves a page continues.
  @Override
  protected void remove(Scope scope, UUID item) {
    invitations.deleteById(item);
    invitations.flush();
  }

  @Override
  protected ListPage<ProfileManagerInvitation> page(Scope scope, PageRequest request) {
    return managerAdministration.pendingManagerInvitations(
        auth.identityOf(scope.recipient()), request);
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

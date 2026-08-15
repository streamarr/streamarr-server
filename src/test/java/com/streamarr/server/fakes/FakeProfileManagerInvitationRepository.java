package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationInsertResult;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import java.util.List;
import java.util.UUID;

public class FakeProfileManagerInvitationRepository
    extends FakeJpaRepository<ProfileManagerInvitation>
    implements ProfileManagerInvitationRepository {

  /**
   * Counts invitations for a profile with the specified status.
   *
   * @param profileId the profile identifier
   * @param status    the invitation status to match
   * @return the number of matching invitations
   */
  @Override
  public long countByProfileIdAndStatus(UUID profileId, ProfileManagerInvitationStatus status) {
    return database.values().stream()
        .filter(invitation -> profileId.equals(invitation.getProfileId()))
        .filter(invitation -> status == invitation.getStatus())
        .count();
  }

  /**
   * Creates a pending invitation unless one already exists for the profile and invited account.
   *
   * @param profileId         the profile associated with the invitation
   * @param invitingAccountId the account sending the invitation
   * @param invitedAccountId  the account receiving the invitation
   * @return the existing or newly created invitation and whether a new invitation was inserted
   */
  @Override
  public synchronized ProfileManagerInvitationInsertResult insertPendingIfAbsent(
      UUID profileId, UUID invitingAccountId, UUID invitedAccountId) {
    var existing =
        database.values().stream()
            .filter(invitation -> profileId.equals(invitation.getProfileId()))
            .filter(invitation -> invitedAccountId.equals(invitation.getInvitedAccountId()))
            .filter(invitation -> invitation.getStatus() == ProfileManagerInvitationStatus.PENDING)
            .findFirst();
    if (existing.isPresent()) {
      return new ProfileManagerInvitationInsertResult(existing.orElseThrow(), false);
    }
    var invitation =
        save(
            ProfileManagerInvitation.builder()
                .profileId(profileId)
                .invitingAccountId(invitingAccountId)
                .invitedAccountId(invitedAccountId)
                .status(ProfileManagerInvitationStatus.PENDING)
                .build());
    return new ProfileManagerInvitationInsertResult(invitation, true);
  }

  /**
   * Finds all invitations associated with a profile.
   *
   * @param profileId the profile identifier
   * @return the invitations associated with the specified profile
   */
  @Override
  public List<ProfileManagerInvitation> findByProfileId(UUID profileId) {
    return database.values().stream()
        .filter(invitation -> profileId.equals(invitation.getProfileId()))
        .toList();
  }
}

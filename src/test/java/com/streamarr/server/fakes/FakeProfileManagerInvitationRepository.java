package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import java.util.List;
import java.util.UUID;

public class FakeProfileManagerInvitationRepository
    extends FakeJpaRepository<ProfileManagerInvitation>
    implements ProfileManagerInvitationRepository {

  @Override
  public long countByProfileIdAndStatus(UUID profileId, ProfileManagerInvitationStatus status) {
    return database.values().stream()
        .filter(invitation -> profileId.equals(invitation.getProfileId()))
        .filter(invitation -> status == invitation.getStatus())
        .count();
  }

  @Override
  public synchronized ProfileManagerInvitation insertPendingIfAbsent(
      UUID profileId, UUID invitingAccountId, UUID invitedAccountId) {
    var existing =
        database.values().stream()
            .filter(invitation -> profileId.equals(invitation.getProfileId()))
            .filter(invitation -> invitedAccountId.equals(invitation.getInvitedAccountId()))
            .filter(invitation -> invitation.getStatus() == ProfileManagerInvitationStatus.PENDING)
            .findFirst();
    return existing.orElseGet(
        () ->
            save(
                ProfileManagerInvitation.builder()
                    .profileId(profileId)
                    .invitingAccountId(invitingAccountId)
                    .invitedAccountId(invitedAccountId)
                    .status(ProfileManagerInvitationStatus.PENDING)
                    .build()));
  }

  @Override
  public List<ProfileManagerInvitation> findByProfileId(UUID profileId) {
    return database.values().stream()
        .filter(invitation -> profileId.equals(invitation.getProfileId()))
        .toList();
  }
}

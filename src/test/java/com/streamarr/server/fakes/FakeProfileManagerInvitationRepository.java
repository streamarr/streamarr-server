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
  public List<ProfileManagerInvitation> findByProfileId(UUID profileId) {
    return database.values().stream()
        .filter(invitation -> profileId.equals(invitation.getProfileId()))
        .toList();
  }
}

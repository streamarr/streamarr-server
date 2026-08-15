package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import java.util.Optional;
import java.util.UUID;

public interface ProfileManagerInvitationRepositoryCustom {

  ProfileManagerInvitationInsertResult insertPendingIfAbsent(
      UUID profileId, UUID invitingAccountId, UUID invitedAccountId);

  Optional<ProfileManagerInvitation> transitionPending(
      ProfileManagerInvitationTransition transition);
}

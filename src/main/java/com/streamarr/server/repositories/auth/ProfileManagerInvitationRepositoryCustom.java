package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import java.util.UUID;

public interface ProfileManagerInvitationRepositoryCustom {

  ProfileManagerInvitation insertPendingIfAbsent(
      UUID profileId, UUID invitingAccountId, UUID invitedAccountId);
}

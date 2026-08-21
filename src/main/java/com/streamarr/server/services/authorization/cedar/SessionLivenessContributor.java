package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.PrimBool;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Whether the token's session still carries this live playback authority. */
@Component
@RequiredArgsConstructor
class SessionLivenessContributor implements FactContributor {

  static final String SESSION_LIVE = "sessionLive";

  private final AuthSessionRepository authSessionRepository;

  @Override
  public FactRequirement provides() {
    return FactRequirement.SESSION_LIVENESS;
  }

  @Override
  public void contribute(
      AuthenticatedIdentity identity, AuthorizationCheck check, EntitySlice slice) {
    slice.principalAttribute(
        SESSION_LIVE, new PrimBool(authSessionRepository.isLive(identity.playbackAuthority())));
  }
}

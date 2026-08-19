package com.streamarr.server.services.identity;

import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.streaming.PlaybackAuthorityGate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * ADR 0018's live playback gate, decided by Cedar (ADR 0025): it runs at stream creation and on
 * every playlist, initialization-segment, and media-segment request, and allows only while the
 * session is live, the Account enabled, the context Household still usable, and the selected
 * Profile actively shared into it. The authority the stream was created for must match the
 * request's identity; anything else, including a decision that could not be made, is denied.
 */
@Service
@RequiredArgsConstructor
public class PlaybackAuthorizationService implements PlaybackAuthorityGate {

  private final AuthorizationService authorizationService;

  @Override
  public boolean allows(PlaybackAuthority authority) {
    var identity = authorizationService.currentIdentity();
    if (identity.profileId() == null || !identity.playbackAuthority().equals(authority)) {
      return false;
    }
    return authorizationService.decide(identity, new Intent.Playback())
        instanceof Decision.Allowed<?>;
  }
}

package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LivePlaybackAuthorityGate implements PlaybackAuthorityGate {

  private final AuthSessionRepository authSessionRepository;

  @Override
  public boolean allows(PlaybackAuthority authority) {
    return authSessionRepository.hasLivePlaybackAuthority(authority);
  }
}

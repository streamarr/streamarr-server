package com.streamarr.server.services.auth;

import com.streamarr.server.properties.CredentialCodeProperties;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
final class ConfiguredInvitationLifetime implements InvitationLifetime {

  private final CredentialCodeProperties properties;

  @Override
  public Duration invitationLifetime() {
    return properties.invitationTtl();
  }
}

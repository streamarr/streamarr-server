package com.streamarr.server.services.user;

import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {

  // Single-user fallback identity until authentication support from issue #163 is wired in.
  private static final UUID SINGLE_USER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  public UUID currentUserId() {
    return SINGLE_USER_ID;
  }
}

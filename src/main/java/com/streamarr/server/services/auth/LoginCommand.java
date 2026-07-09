package com.streamarr.server.services.auth;

import lombok.Builder;

@Builder
public record LoginCommand(String email, String password, String deviceName, String source) {

  @Override
  public String toString() {
    return "LoginCommand[email=%s, deviceName=%s, source=%s]".formatted(email, deviceName, source);
  }
}

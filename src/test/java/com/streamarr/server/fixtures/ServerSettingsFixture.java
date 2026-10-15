package com.streamarr.server.fixtures;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountScopedBuilder;

import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeServerSettingsRepository;
import com.streamarr.server.services.serversettings.ServerSettingsService;

public final class ServerSettingsFixture {

  private ServerSettingsFixture() {}

  /** Settings at their defaults, so every variant job carries the Automatic preset. */
  public static ServerSettingsService defaultServerSettings() {
    return new ServerSettingsService(
        new FakeAuthorizationService(accountScopedBuilder().build()),
        new FakeServerSettingsRepository());
  }
}

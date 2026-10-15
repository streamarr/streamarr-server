package com.streamarr.server.fakes;

import com.streamarr.server.domain.ServerSettings;
import com.streamarr.server.repositories.ServerSettingsRepository;

/** Starts with the one default row, as the server_settings migration does. */
public class FakeServerSettingsRepository extends FakeJpaRepository<ServerSettings>
    implements ServerSettingsRepository {

  public FakeServerSettingsRepository() {
    save(ServerSettings.builder().build());
  }
}

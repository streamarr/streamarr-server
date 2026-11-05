package com.streamarr.server.services.language;

import java.util.UUID;

public sealed interface LanguageOverrideRejection {

  /** No movie, series, season or episode has the id. */
  record ItemNotFound(UUID itemId) implements LanguageOverrideRejection {}
}

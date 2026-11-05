package com.streamarr.server.services.language;

import com.streamarr.server.domain.language.LanguageOverrideChange;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/** Changes the selected Profile's language override of one series, season, episode or movie. */
@Builder
public record SetLanguageOverrideCommand(
    @NonNull UUID itemId, @NonNull LanguageOverrideChange change) {}

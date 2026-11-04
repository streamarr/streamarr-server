package com.streamarr.server.domain.language;

import java.util.UUID;
import lombok.NonNull;

/** The title that one language override belongs to: a series, a season, an episode or a movie. */
public record OverrideScope(@NonNull TitleKind kind, @NonNull UUID titleId) {}

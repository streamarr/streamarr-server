package com.streamarr.server.domain.media;

import lombok.Builder;
import lombok.NonNull;

/**
 * Role-named theme slots derived from {@link AmbientColors}. A slot maps to the same surface in
 * dark and bright themes alike — a button is always {@code accent} filled with {@code onAccent}
 * text — so clients never branch on which theme they received.
 */
@Builder
public record AmbientTheme(
    @NonNull String base,
    @NonNull String panel,
    @NonNull String selected,
    @NonNull String accent,
    @NonNull String onAccent,
    @NonNull String textPrimary,
    @NonNull String textSecondary) {}

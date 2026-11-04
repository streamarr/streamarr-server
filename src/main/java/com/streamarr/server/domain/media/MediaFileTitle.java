package com.streamarr.server.domain.media;

import java.util.Optional;
import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

/**
 * The title of the movie or episode that a media file holds. An episode also names its series, its
 * season number and its episode number; a movie names none of them.
 */
@Builder
public record MediaFileTitle(
    @NonNull Optional<String> title,
    @NonNull Optional<String> seriesTitle,
    @NonNull OptionalInt seasonNumber,
    @NonNull OptionalInt episodeNumber) {}

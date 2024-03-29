package com.streamarr.server.services.parsers.show;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.OptionalInt;

@Getter
@Setter
@Builder(toBuilder = true)
public class EpisodePathResult {

    @Builder.Default
    private OptionalInt seasonNumber = OptionalInt.empty();
    @Builder.Default
    private OptionalInt episodeNumber = OptionalInt.empty();
    @Builder.Default
    private OptionalInt endingEpisodeNumber = OptionalInt.empty();

    private String seriesName;
    private boolean success;
    private boolean onlyDate;
    private LocalDate date;
}

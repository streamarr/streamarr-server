package com.streamarr.server.utils;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@Builder
public class EpisodePathResult {

    private int seasonNumber;
    private int episodeNumber;
    private int endingEpisodeNumber;
    private String seriesName;
    private boolean success;

    private boolean onlyDate;
    private LocalDate date;
}

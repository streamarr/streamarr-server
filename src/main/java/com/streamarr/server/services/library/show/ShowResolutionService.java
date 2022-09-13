package com.streamarr.server.services.library.show;

import com.streamarr.server.services.extraction.show.EpisodePathExtractionService;
import com.streamarr.server.services.extraction.show.SeasonPathExtractionService;
import com.streamarr.server.services.extraction.show.SeriesPathExtractionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ShowResolutionService {

    private final SeriesPathExtractionService seriesPathExtractionService;
    private final SeasonPathExtractionService seasonPathExtractionService;
    private final EpisodePathExtractionService episodePathExtractionService;

    // resolve()?
    // switch over pattern?
    // hasSeriesName? // new series
    // hasSeason? // new season
    // hasEpisode? // new episode

}

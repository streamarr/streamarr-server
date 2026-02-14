package com.streamarr.server.fakes;

import com.streamarr.server.services.metadata.TheMovieDatabaseHttpService;
import com.streamarr.server.services.metadata.TmdbImageDownloader;
import com.streamarr.server.services.metadata.tmdb.TmdbApiException;
import com.streamarr.server.services.metadata.tmdb.TmdbSearchResults;
import com.streamarr.server.services.metadata.tmdb.TmdbTvSearchResults;
import com.streamarr.server.services.metadata.tmdb.TmdbTvSeason;
import com.streamarr.server.services.metadata.tmdb.TmdbTvSeries;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class FakeTmdbHttpService extends TheMovieDatabaseHttpService
    implements TmdbImageDownloader {

  // --- Image downloading ---

  private byte[] imageData;
  private String failOnPath;
  private String interruptOnPath;
  private boolean failAll;
  private long delayMillis;

  // --- Movie search: year (null key = no year) → results ---
  private final Map<String, TmdbSearchResults> movieSearchResponses = new HashMap<>();

  // --- TV search: year (null key = no year) → results ---
  private final Map<String, TmdbTvSearchResults> tvSearchResponses = new HashMap<>();

  // --- TV series metadata: seriesId → metadata ---
  private final Map<String, TmdbTvSeries> tvSeriesMetadata = new HashMap<>();

  // --- TV season details: "seriesId:season" → season ---
  private final Map<String, TmdbTvSeason> tvSeasonDetails = new HashMap<>();

  // --- Season details keys that throw TmdbApiException(404) on first call ---
  private final Set<String> seasonDetailsFirstCallFailures = new HashSet<>();

  public FakeTmdbHttpService() {
    super("", "", "", 10, null, null);
  }

  // --- Image setters ---

  public void setImageData(byte[] imageData) {
    this.imageData = imageData;
  }

  public void setFailOnPath(String path) {
    this.failOnPath = path;
  }

  public void setInterruptOnPath(String path) {
    this.interruptOnPath = path;
  }

  public void setFailAll(boolean failAll) {
    this.failAll = failAll;
  }

  public void setDelayMillis(long delayMillis) {
    this.delayMillis = delayMillis;
  }

  // --- Search setters ---

  public void setMovieSearchResponse(String year, TmdbSearchResults results) {
    movieSearchResponses.put(year, results);
  }

  public void setTvSearchResponse(String year, TmdbTvSearchResults results) {
    tvSearchResponses.put(year, results);
  }

  // --- Metadata setters ---

  public void setTvSeriesMetadata(String seriesId, TmdbTvSeries series) {
    tvSeriesMetadata.put(seriesId, series);
  }

  public void setTvSeasonDetails(String seriesId, int seasonNumber, TmdbTvSeason season) {
    tvSeasonDetails.put(seasonDetailKey(seriesId, seasonNumber), season);
  }

  public void setSeasonDetailsFailOnFirstCall(String seriesId, int seasonNumber) {
    seasonDetailsFirstCallFailures.add(seasonDetailKey(seriesId, seasonNumber));
  }

  // --- Overrides ---

  @Override
  public byte[] downloadImage(String pathFragment) throws IOException, InterruptedException {
    if (delayMillis > 0) {
      Thread.sleep(delayMillis);
    }
    if (failAll) {
      throw new IOException("Simulated download failure for " + pathFragment);
    }
    if (pathFragment.equals(failOnPath)) {
      throw new IOException("Simulated download failure for " + pathFragment);
    }
    if (pathFragment.equals(interruptOnPath)) {
      throw new InterruptedException("Simulated interrupt for " + pathFragment);
    }
    return imageData;
  }

  @Override
  public TmdbSearchResults searchForMovie(VideoFileParserResult videoFileParserResult) {
    return movieSearchResponses.get(videoFileParserResult.year());
  }

  @Override
  public TmdbTvSearchResults searchForTvSeries(VideoFileParserResult parserResult) {
    return tvSearchResponses.get(parserResult.year());
  }

  @Override
  public TmdbTvSeries getTvSeriesMetadata(String seriesId) {
    return tvSeriesMetadata.get(seriesId);
  }

  @Override
  public TmdbTvSeason getTvSeasonDetails(String seriesId, int seasonNumber) throws IOException {
    var key = seasonDetailKey(seriesId, seasonNumber);

    if (seasonDetailsFirstCallFailures.remove(key)) {
      throw new TmdbApiException(404, "The resource you requested could not be found.");
    }

    return tvSeasonDetails.get(key);
  }

  private static String seasonDetailKey(String seriesId, int seasonNumber) {
    return seriesId + ":" + seasonNumber;
  }
}

package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.series.SeriesMetadataProviderResolver;
import com.streamarr.server.services.parsers.show.EpisodePathMetadataParser;
import com.streamarr.server.services.parsers.show.EpisodePathResult;
import com.streamarr.server.services.parsers.show.SeasonPathMetadataParser;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.nio.file.Path;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SeriesFileProcessor {

  private final EpisodePathMetadataParser episodePathMetadataParser;
  private final SeasonPathMetadataParser seasonPathMetadataParser;
  private final SeriesMetadataProviderResolver seriesMetadataProviderResolver;
  private final SeriesService seriesService;
  private final MediaFileRepository mediaFileRepository;
  private final SeasonRepository seasonRepository;
  private final EpisodeRepository episodeRepository;
  private final MutexFactory<String> mutexFactory;

  public SeriesFileProcessor(
      EpisodePathMetadataParser episodePathMetadataParser,
      SeasonPathMetadataParser seasonPathMetadataParser,
      SeriesMetadataProviderResolver seriesMetadataProviderResolver,
      SeriesService seriesService,
      MediaFileRepository mediaFileRepository,
      SeasonRepository seasonRepository,
      EpisodeRepository episodeRepository,
      MutexFactoryProvider mutexFactoryProvider) {
    this.episodePathMetadataParser = episodePathMetadataParser;
    this.seasonPathMetadataParser = seasonPathMetadataParser;
    this.seriesMetadataProviderResolver = seriesMetadataProviderResolver;
    this.seriesService = seriesService;
    this.mediaFileRepository = mediaFileRepository;
    this.seasonRepository = seasonRepository;
    this.episodeRepository = episodeRepository;
    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
  }

  public void process(Library library, MediaFile mediaFile) {
    var parseResult = episodePathMetadataParser.parse(mediaFile.getFilepath());

    if (parseResult.isEmpty() || parseResult.get().getEpisodeNumber().isEmpty()) {
      markAs(mediaFile, MediaFileStatus.METADATA_PARSING_FAILED);
      log.error(
          "Failed to parse episode info from MediaFile id: {} at path: '{}'",
          mediaFile.getId(),
          mediaFile.getFilepath());
      return;
    }

    var episodeNumber = parseResult.get().getEpisodeNumber().getAsInt();

    var filePath = Path.of(mediaFile.getFilepath());
    var parentDir = filePath.getParent();
    var seasonParseResult =
        (parentDir != null)
            ? seasonPathMetadataParser.parse(parentDir.getFileName().toString())
            : Optional.<SeasonPathMetadataParser.Result>empty();

    var seasonNumber = resolveSeasonNumber(parentDir, seasonParseResult, parseResult.get());
    var seriesName = resolveSeriesName(parentDir, seasonParseResult, parseResult.get());

    if (seriesName == null || seriesName.isBlank()) {
      markAs(mediaFile, MediaFileStatus.METADATA_PARSING_FAILED);
      log.error(
          "Could not determine series name from MediaFile id: {} at path: '{}'",
          mediaFile.getId(),
          mediaFile.getFilepath());
      return;
    }

    log.info(
        "Parsed series file: series='{}', season={}, episode={} for MediaFile id: {}",
        seriesName,
        seasonNumber,
        episodeNumber,
        mediaFile.getId());

    var parserResult = VideoFileParserResult.builder().title(seriesName).build();
    var searchResult = seriesMetadataProviderResolver.search(library, parserResult);

    if (searchResult.isEmpty()) {
      markAs(mediaFile, MediaFileStatus.METADATA_SEARCH_FAILED);
      log.error(
          "Failed to find TMDB match for series '{}' from MediaFile id: {} at path: '{}'",
          seriesName,
          mediaFile.getId(),
          mediaFile.getFilepath());
      return;
    }

    enrichSeriesMetadata(library, mediaFile, searchResult.get(), seasonNumber, episodeNumber);
  }

  private int resolveSeasonNumber(
      Path parentDir,
      Optional<SeasonPathMetadataParser.Result> seasonParseResult,
      EpisodePathResult episodeResult) {

    if (parentDir != null
        && seasonParseResult.isPresent()
        && seasonParseResult.get().isSeasonFolder()
        && seasonParseResult.get().seasonNumber().isPresent()) {
      return seasonParseResult.get().seasonNumber().getAsInt();
    }

    return episodeResult.getSeasonNumber().orElse(1);
  }

  private String resolveSeriesName(
      Path parentDir,
      Optional<SeasonPathMetadataParser.Result> seasonParseResult,
      EpisodePathResult episodeResult) {

    if (parentDir == null) {
      return episodeResult.getSeriesName();
    }

    var isSeasonFolder = seasonParseResult.isPresent() && seasonParseResult.get().isSeasonFolder();

    String dirName;
    if (isSeasonFolder && parentDir.getParent() != null) {
      dirName = parentDir.getParent().getFileName().toString();
    } else {
      dirName = parentDir.getFileName().toString();
    }

    var cleanName = dirName.replaceAll("\\s*\\(\\d{4}\\)$", "").trim();

    if (cleanName.isBlank()) {
      return episodeResult.getSeriesName();
    }

    return cleanName;
  }

  private void enrichSeriesMetadata(
      Library library,
      MediaFile mediaFile,
      RemoteSearchResult searchResult,
      int seasonNumber,
      int episodeNumber) {

    var externalIdMutex = mutexFactory.getMutex(searchResult.externalId());

    try {
      externalIdMutex.lock();

      var seriesOpt =
          seriesService
              .findByTmdbId(searchResult.externalId())
              .or(() -> createSeries(library, searchResult));

      if (seriesOpt.isEmpty()) {
        return;
      }

      var series = seriesOpt.get();

      var seasonOpt =
          seasonRepository
              .findBySeriesIdAndSeasonNumber(series.getId(), seasonNumber)
              .or(
                  () ->
                      createSeasonWithEpisodes(
                          library, searchResult.externalId(), seasonNumber, series));

      if (seasonOpt.isEmpty()) {
        return;
      }

      var season = seasonOpt.get();

      var episode =
          episodeRepository
              .findBySeasonIdAndEpisodeNumber(season.getId(), episodeNumber)
              .orElseGet(
                  () ->
                      episodeRepository.saveAndFlush(
                          Episode.builder()
                              .title("Episode " + episodeNumber)
                              .episodeNumber(episodeNumber)
                              .season(season)
                              .library(library)
                              .build()));

      mediaFile.setMediaId(episode.getId());
      markAs(mediaFile, MediaFileStatus.MATCHED);

    } catch (Exception ex) {
      log.error("Failure enriching series metadata for MediaFile id: {}", mediaFile.getId(), ex);
    } finally {
      if (externalIdMutex.isHeldByCurrentThread()) {
        externalIdMutex.unlock();
      }
    }
  }

  private Optional<Series> createSeries(Library library, RemoteSearchResult searchResult) {
    var seriesOpt = seriesMetadataProviderResolver.getMetadata(searchResult, library);

    if (seriesOpt.isEmpty()) {
      log.error("Failed to fetch series metadata for TMDB id '{}'", searchResult.externalId());
      return Optional.empty();
    }

    return Optional.of(seriesService.createSeriesWithAssociations(seriesOpt.get()));
  }

  private Optional<Season> createSeasonWithEpisodes(
      Library library, String seriesExternalId, int seasonNumber, Series series) {
    var seasonDetailsOpt =
        seriesMetadataProviderResolver.getSeasonDetails(library, seriesExternalId, seasonNumber);

    if (seasonDetailsOpt.isEmpty()) {
      log.error(
          "Failed to fetch season {} details for series TMDB id '{}'",
          seasonNumber,
          seriesExternalId);
      return Optional.empty();
    }

    var details = seasonDetailsOpt.get();

    var season =
        seasonRepository.saveAndFlush(
            Season.builder()
                .title(details.name())
                .seasonNumber(details.seasonNumber())
                .overview(details.overview())
                .posterPath(details.posterPath())
                .airDate(details.airDate())
                .series(series)
                .library(library)
                .build());

    var episodes =
        details.episodes().stream()
            .map(
                ep ->
                    Episode.builder()
                        .title(ep.name())
                        .episodeNumber(ep.episodeNumber())
                        .overview(ep.overview())
                        .stillPath(ep.stillPath())
                        .airDate(ep.airDate())
                        .runtime(ep.runtime())
                        .season(season)
                        .library(library)
                        .build())
            .toList();

    episodeRepository.saveAll(episodes);

    return Optional.of(season);
  }

  private void markAs(MediaFile mediaFile, MediaFileStatus status) {
    mediaFile.setStatus(status);
    mediaFileRepository.save(mediaFile);
  }
}

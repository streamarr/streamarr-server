package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.ImageEntityType;
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
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import com.streamarr.server.services.metadata.series.SeasonDetails;
import com.streamarr.server.services.metadata.series.SeriesMetadataProviderResolver;
import com.streamarr.server.services.parsers.show.EpisodePathMetadataParser;
import com.streamarr.server.services.parsers.show.EpisodePathResult;
import com.streamarr.server.services.parsers.show.SeasonPathMetadataParser;
import com.streamarr.server.services.parsers.show.SeriesFolderNameParser;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SeriesFileProcessor {

  private final EpisodePathMetadataParser episodePathMetadataParser;
  private final SeasonPathMetadataParser seasonPathMetadataParser;
  private final SeriesFolderNameParser seriesFolderNameParser;
  private final SeriesMetadataProviderResolver seriesMetadataProviderResolver;
  private final DateBasedEpisodeResolver dateBasedEpisodeResolver;
  private final SeriesService seriesService;
  private final MediaFileRepository mediaFileRepository;
  private final SeasonRepository seasonRepository;
  private final EpisodeRepository episodeRepository;
  private final MutexFactory<String> mutexFactory;
  private final ApplicationEventPublisher eventPublisher;

  public SeriesFileProcessor(
      EpisodePathMetadataParser episodePathMetadataParser,
      SeasonPathMetadataParser seasonPathMetadataParser,
      SeriesFolderNameParser seriesFolderNameParser,
      SeriesMetadataProviderResolver seriesMetadataProviderResolver,
      DateBasedEpisodeResolver dateBasedEpisodeResolver,
      SeriesService seriesService,
      MediaFileRepository mediaFileRepository,
      SeasonRepository seasonRepository,
      EpisodeRepository episodeRepository,
      MutexFactoryProvider mutexFactoryProvider,
      ApplicationEventPublisher eventPublisher) {
    this.episodePathMetadataParser = episodePathMetadataParser;
    this.seasonPathMetadataParser = seasonPathMetadataParser;
    this.seriesFolderNameParser = seriesFolderNameParser;
    this.seriesMetadataProviderResolver = seriesMetadataProviderResolver;
    this.dateBasedEpisodeResolver = dateBasedEpisodeResolver;
    this.seriesService = seriesService;
    this.mediaFileRepository = mediaFileRepository;
    this.seasonRepository = seasonRepository;
    this.episodeRepository = episodeRepository;
    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
    this.eventPublisher = eventPublisher;
  }

  public void process(Library library, MediaFile mediaFile) {
    var filePath = FilepathCodec.decode(mediaFile.getFilepathUri());
    var parseResult = episodePathMetadataParser.parse(filePath.toString());

    if (parseResult.isEmpty()) {
      markAs(mediaFile, MediaFileStatus.METADATA_PARSING_FAILED);
      log.error(
          "Failed to parse episode info from MediaFile id: {} at path: '{}'",
          mediaFile.getId(),
          mediaFile.getFilepathUri());
      return;
    }

    var parsed = parseResult.get();
    var isDateOnly = isDateOnlyEpisode(parsed);

    if (parsed.getEpisodeNumber().isEmpty() && !isDateOnly) {
      markAs(mediaFile, MediaFileStatus.METADATA_PARSING_FAILED);
      log.error(
          "Failed to parse episode info from MediaFile id: {} at path: '{}'",
          mediaFile.getId(),
          mediaFile.getFilepathUri());
      return;
    }

    var parentDir = filePath.getParent();
    var seasonParseResult =
        (parentDir != null)
            ? seasonPathMetadataParser.parse(parentDir.getFileName().toString())
            : Optional.<SeasonPathMetadataParser.Result>empty();

    var parserResult = resolveSeriesInfo(parentDir, seasonParseResult, parsed);

    if (parserResult.title() == null || parserResult.title().isBlank()) {
      markAs(mediaFile, MediaFileStatus.METADATA_PARSING_FAILED);
      log.error(
          "Could not determine series name from MediaFile id: {} at path: '{}'",
          mediaFile.getId(),
          mediaFile.getFilepathUri());
      return;
    }

    var searchResult = seriesMetadataProviderResolver.search(library, parserResult);

    if (searchResult.isEmpty()) {
      markAs(mediaFile, MediaFileStatus.METADATA_SEARCH_FAILED);
      log.error(
          "Failed to find TMDB match for series '{}' from MediaFile id: {} at path: '{}'",
          parserResult.title(),
          mediaFile.getId(),
          mediaFile.getFilepathUri());
      return;
    }

    if (isDateOnly) {
      processDateOnlyEpisode(library, mediaFile, searchResult.get(), parsed);
      return;
    }

    var episodeNumber = parsed.getEpisodeNumber().getAsInt();
    var seasonNumber = resolveSeasonNumber(parentDir, seasonParseResult, parsed);

    log.info(
        "Parsed series file: series='{}', season={}, episode={} for MediaFile id: {}",
        parserResult.title(),
        seasonNumber,
        episodeNumber,
        mediaFile.getId());

    enrichSeriesMetadata(library, mediaFile, searchResult.get(), seasonNumber, episodeNumber);
  }

  private void processDateOnlyEpisode(
      Library library,
      MediaFile mediaFile,
      RemoteSearchResult searchResult,
      EpisodePathResult parseResult) {
    var dateResolution =
        dateBasedEpisodeResolver.resolve(library, searchResult.externalId(), parseResult.getDate());

    if (dateResolution.isEmpty()) {
      markAs(mediaFile, MediaFileStatus.METADATA_SEARCH_FAILED);
      log.error(
          "Failed to resolve date {} to episode for series TMDB id '{}', MediaFile id: {}",
          parseResult.getDate(),
          searchResult.externalId(),
          mediaFile.getId());
      return;
    }

    log.info(
        "Resolved date {} to season={}, episode={} for series TMDB id '{}', MediaFile id: {}",
        parseResult.getDate(),
        dateResolution.get().seasonNumber(),
        dateResolution.get().episodeNumber(),
        searchResult.externalId(),
        mediaFile.getId());

    enrichSeriesMetadata(
        library,
        mediaFile,
        searchResult,
        dateResolution.get().seasonNumber(),
        dateResolution.get().episodeNumber());
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

  private VideoFileParserResult resolveSeriesInfo(
      Path parentDir,
      Optional<SeasonPathMetadataParser.Result> seasonParseResult,
      EpisodePathResult episodeResult) {

    if (parentDir == null) {
      return VideoFileParserResult.builder().title(episodeResult.getSeriesName()).build();
    }

    var isSeasonFolder = seasonParseResult.isPresent() && seasonParseResult.get().isSeasonFolder();

    String dirName;
    if (isSeasonFolder && parentDir.getParent() != null) {
      dirName = parentDir.getParent().getFileName().toString();
    } else {
      dirName = parentDir.getFileName().toString();
    }

    var result = seriesFolderNameParser.parse(dirName);

    if (result.title() == null || result.title().isBlank()) {
      return VideoFileParserResult.builder().title(episodeResult.getSeriesName()).build();
    }

    return result;
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
      var seasonOpt = seasonRepository.findBySeriesIdAndSeasonNumber(series.getId(), seasonNumber);

      var effectiveSeasonNumber =
          resolveEffectiveSeasonNumber(
              library, searchResult.externalId(), seasonNumber, seasonOpt);

      if (effectiveSeasonNumber.isEmpty()) {
        return;
      }

      if (effectiveSeasonNumber.getAsInt() != seasonNumber) {
        seasonOpt =
            seasonRepository.findBySeriesIdAndSeasonNumber(
                series.getId(), effectiveSeasonNumber.getAsInt());
      }

      if (seasonOpt.isEmpty()) {
        seasonOpt =
            createSeasonWithEpisodes(
                library, searchResult.externalId(), effectiveSeasonNumber.getAsInt(), series);
      }

      if (seasonOpt.isEmpty()) {
        return;
      }

      var episode = findOrCreateEpisode(seasonOpt.get(), library, episodeNumber);

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

  private OptionalInt resolveEffectiveSeasonNumber(
      Library library, String externalId, int seasonNumber, Optional<Season> existingSeason) {
    if (existingSeason.isPresent()
        || seasonNumber < EpisodePathMetadataParser.EARLIEST_TV_BROADCAST_YEAR) {
      return OptionalInt.of(seasonNumber);
    }

    var resolved =
        seriesMetadataProviderResolver.resolveSeasonNumber(library, externalId, seasonNumber);

    if (resolved.isEmpty()) {
      log.warn(
          "Could not resolve year-based season {} for series TMDB id '{}'",
          seasonNumber,
          externalId);
    }

    return resolved;
  }

  private Episode findOrCreateEpisode(Season season, Library library, int episodeNumber) {
    return episodeRepository
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
  }

  private Optional<Series> createSeries(Library library, RemoteSearchResult searchResult) {
    var metadataResult = seriesMetadataProviderResolver.getMetadata(searchResult, library);

    if (metadataResult.isEmpty()) {
      log.error("Failed to fetch series metadata for TMDB id '{}'", searchResult.externalId());
      return Optional.empty();
    }

    return Optional.of(seriesService.createSeriesWithAssociations(metadataResult.get()));
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
                .airDate(details.airDate())
                .series(series)
                .library(library)
                .build());

    publishImageEvent(season.getId(), ImageEntityType.SEASON, details.imageSources());

    var episodes =
        details.episodes().stream()
            .map(
                ep ->
                    Episode.builder()
                        .title(ep.name())
                        .episodeNumber(ep.episodeNumber())
                        .overview(ep.overview())
                        .airDate(ep.airDate())
                        .runtime(ep.runtime())
                        .season(season)
                        .library(library)
                        .build())
            .toList();

    var savedEpisodes = episodeRepository.saveAll(episodes);

    publishEpisodeImageEvents(savedEpisodes, details.episodes());

    return Optional.of(season);
  }

  private void publishImageEvent(
      UUID entityId, ImageEntityType entityType, List<ImageSource> sources) {
    if (!sources.isEmpty()) {
      eventPublisher.publishEvent(new MetadataEnrichedEvent(entityId, entityType, sources));
    }
  }

  private void publishEpisodeImageEvents(
      Collection<? extends Episode> savedEpisodes,
      List<SeasonDetails.EpisodeDetails> episodeDetails) {
    for (var episode : savedEpisodes) {
      episodeDetails.stream()
          .filter(ed -> ed.episodeNumber() == episode.getEpisodeNumber())
          .findFirst()
          .ifPresent(
              ed -> publishImageEvent(episode.getId(), ImageEntityType.EPISODE, ed.imageSources()));
    }
  }

  private boolean isDateOnlyEpisode(EpisodePathResult result) {
    return result.isOnlyDate() && result.getDate() != null;
  }

  private void markAs(MediaFile mediaFile, MediaFileStatus status) {
    mediaFile.setStatus(status);
    mediaFileRepository.save(mediaFile);
  }
}

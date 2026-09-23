package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.MatchingFailure;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.services.SeasonWithEpisodesRequest;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.metadata.MetadataFetchOutcome;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.Found;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.NotFound;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.TemporarilyUnavailable;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.series.SeriesMetadataProviderResolver;
import com.streamarr.server.services.parsers.show.EpisodePathMetadataParser;
import com.streamarr.server.services.parsers.show.EpisodePathResult;
import com.streamarr.server.services.parsers.show.SeasonPathMetadataParser;
import com.streamarr.server.services.parsers.show.SeriesFolderNameParser;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.util.Optional;
import java.util.OptionalInt;
import lombok.extern.slf4j.Slf4j;
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
      MutexFactoryProvider mutexFactoryProvider) {
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
  }

  public void process(FileDiscovery discovery, MediaFile mediaFile) {
    var filepath = FilepathCodec.pathOf(mediaFile.getFilepathUri());
    var parseResult = episodePathMetadataParser.parse(filepath);

    if (parseResult.isEmpty()) {
      recordFailure(mediaFile, MatchingFailure.of(MediaFileStatus.METADATA_PARSING_FAILED));
      log.error(
          "Failed to parse episode info from MediaFile id: {} at path: '{}'",
          mediaFile.getId(),
          mediaFile.getFilepathUri());
      return;
    }

    var parsed = parseResult.get();
    var isDateOnly = isDateOnlyEpisode(parsed);

    if (parsed.getEpisodeNumber().isEmpty() && !isDateOnly) {
      recordFailure(mediaFile, MatchingFailure.of(MediaFileStatus.METADATA_PARSING_FAILED));
      log.error(
          "Failed to parse episode info from MediaFile id: {} at path: '{}'",
          mediaFile.getId(),
          mediaFile.getFilepathUri());
      return;
    }

    var seasonFolderName = FilepathCodec.parentNameOf(mediaFile.getFilepathUri());
    var seasonParseResult = seasonFolderName.flatMap(seasonPathMetadataParser::parse);

    var parserResult =
        resolveSeriesInfo(
            seriesFolderNameOf(mediaFile.getFilepathUri(), seasonParseResult), parsed);

    if (parserResult.title() == null || parserResult.title().isBlank()) {
      recordFailure(mediaFile, MatchingFailure.of(MediaFileStatus.METADATA_PARSING_FAILED));
      log.error(
          "Could not determine series name from MediaFile id: {} at path: '{}'",
          mediaFile.getId(),
          mediaFile.getFilepathUri());
      return;
    }

    var searchOutcome = seriesMetadataProviderResolver.search(discovery.library(), parserResult);

    switch (searchOutcome) {
      case NotFound _ -> {
        recordFailure(mediaFile, MatchingFailure.of(MediaFileStatus.METADATA_NOT_FOUND));
        log.error(
            "Failed to find TMDB match for series '{}' from MediaFile id: {} at path: '{}'",
            parserResult.title(),
            mediaFile.getId(),
            mediaFile.getFilepathUri());
      }
      case TemporarilyUnavailable unavailable -> {
        recordFailure(
            mediaFile,
            new MatchingFailure(MediaFileStatus.METADATA_UNAVAILABLE, unavailable.reason()));
        log.error(
            "Metadata provider unavailable for series '{}' from MediaFile id: {} at path: '{}'",
            parserResult.title(),
            mediaFile.getId(),
            mediaFile.getFilepathUri(),
            unavailable.cause());
      }
      case Found(var searchResult) -> {
        if (isDateOnly) {
          processDateOnlyEpisode(discovery, mediaFile, searchResult, parsed);
          return;
        }

        var episodeNumber = parsed.getEpisodeNumber().getAsInt();
        var seasonNumber = resolveSeasonNumber(seasonParseResult, parsed);

        log.info(
            "Parsed series file: series='{}', season={}, episode={} for MediaFile id: {}",
            parserResult.title(),
            seasonNumber,
            episodeNumber,
            mediaFile.getId());

        enrichSeriesMetadata(discovery, mediaFile, searchResult, seasonNumber, episodeNumber);
      }
    }
  }

  private void processDateOnlyEpisode(
      FileDiscovery discovery,
      MediaFile mediaFile,
      RemoteSearchResult searchResult,
      EpisodePathResult parseResult) {
    var dateResolution =
        dateBasedEpisodeResolver.resolve(
            discovery.library(), searchResult.externalId(), parseResult.getDate());

    if (!(dateResolution instanceof MetadataFetchOutcome.Found(var resolution))) {
      log.error(
          "Failed to resolve date {} to episode for series TMDB id '{}', MediaFile id: {}",
          parseResult.getDate(),
          searchResult.externalId(),
          mediaFile.getId());
      recordFetchFailure(mediaFile, dateResolution);
      return;
    }

    log.info(
        "Resolved date {} to season={}, episode={} for series TMDB id '{}', MediaFile id: {}",
        parseResult.getDate(),
        resolution.seasonNumber(),
        resolution.episodeNumber(),
        searchResult.externalId(),
        mediaFile.getId());

    enrichSeriesMetadata(
        discovery, mediaFile, searchResult, resolution.seasonNumber(), resolution.episodeNumber());
  }

  private int resolveSeasonNumber(
      Optional<SeasonPathMetadataParser.Result> seasonParseResult,
      EpisodePathResult episodeResult) {

    var folderSeasonNumber =
        seasonParseResult
            .filter(SeasonPathMetadataParser.Result::isSeasonFolder)
            .map(SeasonPathMetadataParser.Result::seasonNumber)
            .orElseGet(OptionalInt::empty);

    return folderSeasonNumber.orElseGet(() -> episodeResult.getSeasonNumber().orElse(1));
  }

  /** The series folder: the season folder's parent when present, or the file's own folder. */
  private Optional<String> seriesFolderNameOf(
      String filepathUri, Optional<SeasonPathMetadataParser.Result> seasonParseResult) {

    var folderName = FilepathCodec.parentNameOf(filepathUri);

    if (folderName.isEmpty() || !isSeasonFolder(seasonParseResult)) {
      return folderName;
    }

    return FilepathCodec.grandparentNameOf(filepathUri);
  }

  private boolean isSeasonFolder(Optional<SeasonPathMetadataParser.Result> seasonParseResult) {
    return seasonParseResult.filter(SeasonPathMetadataParser.Result::isSeasonFolder).isPresent();
  }

  private VideoFileParserResult resolveSeriesInfo(
      Optional<String> seriesFolderName, EpisodePathResult episodeResult) {

    if (seriesFolderName.isEmpty()) {
      return titleOf(episodeResult);
    }

    var result = seriesFolderNameParser.parse(seriesFolderName.get());

    if (result.title() == null || result.title().isBlank()) {
      return titleOf(episodeResult);
    }

    return result;
  }

  private VideoFileParserResult titleOf(EpisodePathResult episodeResult) {
    return VideoFileParserResult.builder().title(episodeResult.getSeriesName()).build();
  }

  private void enrichSeriesMetadata(
      FileDiscovery discovery,
      MediaFile mediaFile,
      RemoteSearchResult searchResult,
      int seasonNumber,
      int episodeNumber) {

    var externalIdMutex = mutexFactory.getMutex(searchResult.externalId());

    try {
      externalIdMutex.lockInterruptibly();

      var seriesOutcome = findOrCreateSeries(discovery, searchResult);

      if (!(seriesOutcome instanceof MetadataFetchOutcome.Found(var series))) {
        recordFetchFailure(mediaFile, seriesOutcome);
        return;
      }

      var seasonOpt = seasonRepository.findBySeriesIdAndSeasonNumber(series.getId(), seasonNumber);

      var effectiveSeason =
          resolveEffectiveSeasonNumber(
              discovery.library(), searchResult.externalId(), seasonNumber, seasonOpt);

      if (!(effectiveSeason instanceof MetadataFetchOutcome.Found(var effectiveSeasonNumber))) {
        recordFetchFailure(mediaFile, effectiveSeason);
        return;
      }

      if (effectiveSeasonNumber != seasonNumber) {
        seasonOpt =
            seasonRepository.findBySeriesIdAndSeasonNumber(series.getId(), effectiveSeasonNumber);
      }

      var seasonOutcome =
          seasonOpt
              .<MetadataFetchOutcome<Season>>map(MetadataFetchOutcome.Found::new)
              .orElseGet(
                  () ->
                      createSeasonWithEpisodes(
                          discovery, searchResult.externalId(), effectiveSeasonNumber, series));

      if (!(seasonOutcome instanceof MetadataFetchOutcome.Found(var season))) {
        recordFetchFailure(mediaFile, seasonOutcome);
        return;
      }

      var episode = findOrCreateEpisode(season, discovery.library(), episodeNumber);

      mediaFile.setMediaId(episode.getId());
      markAsMatched(mediaFile);

    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("Enrichment interrupted for MediaFile id: {}", mediaFile.getId(), ex);
    } catch (Exception ex) {
      log.error("Failure enriching series metadata for MediaFile id: {}", mediaFile.getId(), ex);
      recordFailure(
          mediaFile,
          new MatchingFailure(MediaFileStatus.ENRICHMENT_FAILED, ItemFailureReason.TEMPORARY));
    } finally {
      if (externalIdMutex.isHeldByCurrentThread()) {
        externalIdMutex.unlock();
      }
    }
  }

  private MetadataFetchOutcome<Integer> resolveEffectiveSeasonNumber(
      Library library, String externalId, int seasonNumber, Optional<Season> existingSeason) {
    if (existingSeason.isPresent()
        || seasonNumber < EpisodePathMetadataParser.EARLIEST_TV_BROADCAST_YEAR) {
      return new MetadataFetchOutcome.Found<>(seasonNumber);
    }

    var resolved =
        seriesMetadataProviderResolver.resolveSeasonNumber(library, externalId, seasonNumber);

    if (resolved instanceof MetadataFetchOutcome.NotFound<Integer>) {
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

  private MetadataFetchOutcome<Series> findOrCreateSeries(
      FileDiscovery discovery, RemoteSearchResult searchResult) {
    var existing = seriesService.findByTmdbId(searchResult.externalId());
    if (existing.isPresent()) {
      return new MetadataFetchOutcome.Found<>(existing.get());
    }

    return seriesMetadataProviderResolver
        .getMetadata(searchResult, discovery.library())
        .map(
            metadataResult ->
                seriesService.createSeriesWithAssociations(metadataResult, discovery.artworkRun()));
  }

  private MetadataFetchOutcome<Season> createSeasonWithEpisodes(
      FileDiscovery discovery, String seriesExternalId, int seasonNumber, Series series) {
    return seriesMetadataProviderResolver
        .getSeasonDetails(discovery.library(), seriesExternalId, seasonNumber)
        .map(
            seasonDetails ->
                seriesService.createSeasonWithEpisodes(
                    SeasonWithEpisodesRequest.builder()
                        .series(series)
                        .details(seasonDetails)
                        .library(discovery.library())
                        .artworkRun(discovery.artworkRun())
                        .build()));
  }

  private void recordFetchFailure(MediaFile mediaFile, MetadataFetchOutcome<?> outcome) {
    log.error("Failed to fetch series metadata for MediaFile id: {}", mediaFile.getId());
    if (outcome instanceof MetadataFetchOutcome.Failed<?> failed) {
      recordFailure(
          mediaFile, new MatchingFailure(MediaFileStatus.ENRICHMENT_FAILED, failed.reason()));
      return;
    }

    recordFailure(mediaFile, MatchingFailure.of(MediaFileStatus.METADATA_NOT_FOUND));
  }

  private boolean isDateOnlyEpisode(EpisodePathResult result) {
    return result.isOnlyDate() && result.getDate() != null;
  }

  private void markAsMatched(MediaFile mediaFile) {
    mediaFile.setStatus(MediaFileStatus.MATCHED);
    mediaFile.setFailureReason(null);
    mediaFileRepository.save(mediaFile);
  }

  private void recordFailure(MediaFile mediaFile, MatchingFailure failure) {
    mediaFileRepository.tryRecordMatchingFailure(mediaFile.getId(), failure);
  }
}

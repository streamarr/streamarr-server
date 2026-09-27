package com.streamarr.server.services.library;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.MatchingFailure;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.metadata.MetadataFetchOutcome;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.Found;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.NotFound;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.TemporarilyUnavailable;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderResolver;
import com.streamarr.server.services.parsers.video.DefaultVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.ExternalIdVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MovieFileProcessor {

  private final DefaultVideoFileMetadataParser defaultVideoFileMetadataParser;
  private final ExternalIdVideoFileMetadataParser externalIdVideoFileMetadataParser;
  private final MovieMetadataProviderResolver movieMetadataProviderResolver;
  private final MovieService movieService;
  private final MediaFileRepository mediaFileRepository;
  private final MutexFactory<String> mutexFactory;

  public MovieFileProcessor(
      DefaultVideoFileMetadataParser defaultVideoFileMetadataParser,
      ExternalIdVideoFileMetadataParser externalIdVideoFileMetadataParser,
      MovieMetadataProviderResolver movieMetadataProviderResolver,
      MovieService movieService,
      MediaFileRepository mediaFileRepository,
      MutexFactoryProvider mutexFactoryProvider) {
    this.defaultVideoFileMetadataParser = defaultVideoFileMetadataParser;
    this.externalIdVideoFileMetadataParser = externalIdVideoFileMetadataParser;
    this.movieMetadataProviderResolver = movieMetadataProviderResolver;
    this.movieService = movieService;
    this.mediaFileRepository = mediaFileRepository;
    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
  }

  /**
   * Result of the remote stage for one file. {@link Matched#details()} fetches the provider's
   * details: lazily from {@link #identify} (called under the provider-id mutex only when the movie
   * does not exist yet, the original behavior) or replaying a result fetched eagerly by {@link
   * #identifyWithDetails}.
   */
  public sealed interface MovieIdentification {

    record ParseFailed() implements MovieIdentification {}

    record NotFound() implements MovieIdentification {}

    record Unavailable(TemporarilyUnavailable outcome) implements MovieIdentification {}

    record Matched(RemoteSearchResult match, DetailsSource details)
        implements MovieIdentification {}
  }

  /** Provider details for a matched file. May throw, exactly as the provider call can. */
  @FunctionalInterface
  public interface DetailsSource {
    MetadataFetchOutcome<MetadataResult<Movie>> fetch() throws Exception;
  }

  /** All stages back to back: the original per-file behavior. */
  public void process(FileDiscovery discovery, MediaFile mediaFile) {
    persist(discovery, mediaFile, identify(discovery, mediaFile));
  }

  /**
   * Remote stage: parse the filename and search the provider. Holds no database connection.
   * Details are left to {@link #persist}, which fetches them under the provider-id mutex only when
   * the movie does not exist yet.
   */
  public MovieIdentification identify(FileDiscovery discovery, MediaFile mediaFile) {
    return search(discovery, mediaFile, this::lazyDetails);
  }

  /**
   * Remote stage for a pipeline: parse, search, and fetch details unconditionally, outside any
   * mutex. Holds no database connection. A duplicate file of an existing movie therefore costs a
   * details request the original path would have skipped.
   */
  public MovieIdentification identifyWithDetails(FileDiscovery discovery, MediaFile mediaFile) {
    return search(discovery, mediaFile, this::eagerDetails);
  }

  /**
   * Database stage: marks a failed identification, or under the provider-id mutex attaches the file
   * to the existing movie or creates the movie from the details, then marks the file matched.
   */
  public void persist(
      FileDiscovery discovery, MediaFile mediaFile, MovieIdentification identification) {
    switch (identification) {
      case MovieIdentification.ParseFailed _ -> {
        markMatchingFailed(mediaFile, MatchingFailure.of(MediaFileStatus.METADATA_PARSING_FAILED));

        log.error(
            "Failed to parse MediaFile id: {} at path: '{}'",
            mediaFile.getId(),
            mediaFile.getFilepathUri());
      }
      case MovieIdentification.NotFound _ -> {
        markMatchingFailed(mediaFile, MatchingFailure.of(MediaFileStatus.METADATA_NOT_FOUND));

        log.error(
            "Failed to find matching search result for MediaFile id: {} at path: '{}'",
            mediaFile.getId(),
            mediaFile.getFilepathUri());
      }
      case MovieIdentification.Unavailable(var unavailable) -> {
        markMatchingFailed(
            mediaFile,
            new MatchingFailure(MediaFileStatus.METADATA_UNAVAILABLE, unavailable.reason()));

        log.error(
            "Metadata provider unavailable for MediaFile id: {} at path: '{}'",
            mediaFile.getId(),
            mediaFile.getFilepathUri(),
            unavailable.cause());
      }
      case MovieIdentification.Matched(var movieSearchResult, var details) -> {
        log.info(
            "Found metadata search result during enrichment for MediaFile id: {}. Metadata provider: {} and External id: {}",
            mediaFile.getId(),
            movieSearchResult.externalSourceType(),
            movieSearchResult.externalId());

        enrichMovieMetadata(discovery, mediaFile, movieSearchResult, details)
            .ifPresent(failure -> markMatchingFailed(mediaFile, failure));
      }
    }
  }

  private interface DetailsPolicy {
    DetailsSource detailsFor(FileDiscovery discovery, RemoteSearchResult match);
  }

  private DetailsSource lazyDetails(FileDiscovery discovery, RemoteSearchResult match) {
    return () -> movieMetadataProviderResolver.getMetadata(match, discovery.library());
  }

  private DetailsSource eagerDetails(FileDiscovery discovery, RemoteSearchResult match) {
    try {
      var fetched = movieMetadataProviderResolver.getMetadata(match, discovery.library());
      return () -> fetched;
    } catch (Exception thrown) {
      return () -> {
        throw thrown;
      };
    }
  }

  private MovieIdentification search(
      FileDiscovery discovery, MediaFile mediaFile, DetailsPolicy detailsPolicy) {
    var mediaInformationResult = parseMediaFileForMovieInfo(mediaFile);

    if (mediaInformationResult.isEmpty()) {
      return new MovieIdentification.ParseFailed();
    }

    log.info(
        "Parsed filename for MediaFile id: {}. Title: {} and Year: {}",
        mediaFile.getId(),
        mediaInformationResult.get().title(),
        mediaInformationResult.get().year());

    var searchOutcome =
        movieMetadataProviderResolver.search(discovery.library(), mediaInformationResult.get());

    return switch (searchOutcome) {
      case NotFound _ -> new MovieIdentification.NotFound();
      case TemporarilyUnavailable unavailable -> new MovieIdentification.Unavailable(unavailable);
      case Found(var movieSearchResult) ->
          new MovieIdentification.Matched(
              movieSearchResult, detailsPolicy.detailsFor(discovery, movieSearchResult));
    };
  }

  private Optional<VideoFileParserResult> parseMediaFileForMovieInfo(MediaFile mediaFile) {
    var filename = FilepathCodec.filenameOf(mediaFile.getFilepathUri());
    var result = defaultVideoFileMetadataParser.parse(filename);

    if (result.isEmpty() || StringUtils.isEmpty(result.get().title())) {
      return Optional.empty();
    }

    var folderResult = parseFolderName(mediaFile).filter(fr -> fr.year() != null);
    if (result.get().year() == null && folderResult.isPresent()) {
      result = folderResult;
    }

    var externalIdResult = externalIdVideoFileMetadataParser.parse(filename);
    if (externalIdResult.isEmpty()) {
      return result;
    }

    return Optional.of(
        VideoFileParserResult.builder()
            .title(result.get().title())
            .year(result.get().year())
            .externalId(externalIdResult.get().externalId())
            .externalSource(externalIdResult.get().externalSource())
            .build());
  }

  private Optional<VideoFileParserResult> parseFolderName(MediaFile mediaFile) {
    return FilepathCodec.parentNameOf(mediaFile.getFilepathUri())
        .flatMap(defaultVideoFileMetadataParser::parse);
  }

  private Optional<MatchingFailure> enrichMovieMetadata(
      FileDiscovery discovery,
      MediaFile mediaFile,
      RemoteSearchResult remoteSearchResult,
      DetailsSource details) {

    var externalIdMutex = mutexFactory.getMutex(remoteSearchResult.externalId());

    try {
      externalIdMutex.lockInterruptibly();

      return updateOrSaveEnrichedMovie(discovery, mediaFile, remoteSearchResult, details);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("Enrichment interrupted for MediaFile id: {}", mediaFile.getId(), ex);
      return Optional.empty();
    } catch (Exception ex) {
      log.error("Failure enriching movie metadata:", ex);
      return Optional.of(
          new MatchingFailure(MediaFileStatus.ENRICHMENT_FAILED, ItemFailureReason.TEMPORARY));
    } finally {
      if (externalIdMutex.isHeldByCurrentThread()) {
        externalIdMutex.unlock();
      }
    }
  }

  private Optional<MatchingFailure> updateOrSaveEnrichedMovie(
      FileDiscovery discovery,
      MediaFile mediaFile,
      RemoteSearchResult remoteSearchResult,
      DetailsSource details)
      throws Exception {
    var optionalMovie =
        movieService.addMediaFileToMovieByTmdbId(remoteSearchResult.externalId(), mediaFile);

    if (optionalMovie.isPresent()) {
      markMediaFileAsMatched(mediaFile);
      return Optional.empty();
    }

    return switch (details.fetch()) {
      case MetadataFetchOutcome.Found(var metadataResult) -> {
        movieService.createMovieWithAssociations(metadataResult, mediaFile, discovery.artworkRun());
        markMediaFileAsMatched(mediaFile);
        yield Optional.empty();
      }
      case MetadataFetchOutcome.NotFound<?> _ ->
          Optional.of(MatchingFailure.of(MediaFileStatus.METADATA_NOT_FOUND));
      case MetadataFetchOutcome.Failed<?> failed ->
          Optional.of(new MatchingFailure(MediaFileStatus.ENRICHMENT_FAILED, failed.reason()));
    };
  }

  private void markMediaFileAsMatched(MediaFile mediaFile) {
    mediaFile.setStatus(MediaFileStatus.MATCHED);
    mediaFile.setFailureReason(null);
    mediaFileRepository.save(mediaFile);
  }

  private void markMatchingFailed(MediaFile mediaFile, MatchingFailure failure) {
    mediaFileRepository.tryMarkMatchingFailed(mediaFile.getId(), failure);
  }
}

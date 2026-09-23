package com.streamarr.server.services.library;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.MatchingFailure;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.metadata.MetadataFetchOutcome;
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

  public void process(FileDiscovery discovery, MediaFile mediaFile) {
    var mediaInformationResult = parseMediaFileForMovieInfo(mediaFile);

    if (mediaInformationResult.isEmpty()) {
      recordFailure(mediaFile, MatchingFailure.of(MediaFileStatus.METADATA_PARSING_FAILED));

      log.error(
          "Failed to parse MediaFile id: {} at path: '{}'",
          mediaFile.getId(),
          mediaFile.getFilepathUri());

      return;
    }

    log.info(
        "Parsed filename for MediaFile id: {}. Title: {} and Year: {}",
        mediaFile.getId(),
        mediaInformationResult.get().title(),
        mediaInformationResult.get().year());

    var searchOutcome =
        movieMetadataProviderResolver.search(discovery.library(), mediaInformationResult.get());

    switch (searchOutcome) {
      case NotFound _ -> {
        recordFailure(mediaFile, MatchingFailure.of(MediaFileStatus.METADATA_NOT_FOUND));

        log.error(
            "Failed to find matching search result for MediaFile id: {} at path: '{}'",
            mediaFile.getId(),
            mediaFile.getFilepathUri());
      }
      case TemporarilyUnavailable unavailable -> {
        recordFailure(
            mediaFile,
            new MatchingFailure(MediaFileStatus.METADATA_UNAVAILABLE, unavailable.reason()));

        log.error(
            "Metadata provider unavailable for MediaFile id: {} at path: '{}'",
            mediaFile.getId(),
            mediaFile.getFilepathUri(),
            unavailable.cause());
      }
      case Found(var movieSearchResult) -> {
        log.info(
            "Found metadata search result during enrichment for MediaFile id: {}. Metadata provider: {} and External id: {}",
            mediaFile.getId(),
            movieSearchResult.externalSourceType(),
            movieSearchResult.externalId());

        enrichMovieMetadata(discovery, mediaFile, movieSearchResult)
            .ifPresent(failure -> recordFailure(mediaFile, failure));
      }
    }
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
      FileDiscovery discovery, MediaFile mediaFile, RemoteSearchResult remoteSearchResult) {

    var externalIdMutex = mutexFactory.getMutex(remoteSearchResult.externalId());

    try {
      externalIdMutex.lockInterruptibly();

      return updateOrSaveEnrichedMovie(discovery, mediaFile, remoteSearchResult);
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
      FileDiscovery discovery, MediaFile mediaFile, RemoteSearchResult remoteSearchResult) {
    var optionalMovie =
        movieService.addMediaFileToMovieByTmdbId(remoteSearchResult.externalId(), mediaFile);

    if (optionalMovie.isPresent()) {
      markMediaFileAsMatched(mediaFile);
      return Optional.empty();
    }

    return switch (movieMetadataProviderResolver.getMetadata(
        remoteSearchResult, discovery.library())) {
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

  private void recordFailure(MediaFile mediaFile, MatchingFailure failure) {
    mediaFileRepository.tryRecordMatchingFailure(mediaFile.getId(), failure);
  }
}

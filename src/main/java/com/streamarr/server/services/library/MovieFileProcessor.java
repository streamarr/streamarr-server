package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderResolver;
import com.streamarr.server.services.parsers.video.DefaultVideoFileMetadataParser;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MovieFileProcessor {

  private final DefaultVideoFileMetadataParser defaultVideoFileMetadataParser;
  private final MovieMetadataProviderResolver movieMetadataProviderResolver;
  private final MovieService movieService;
  private final MediaFileRepository mediaFileRepository;
  private final MutexFactory<String> mutexFactory;

  public MovieFileProcessor(
      DefaultVideoFileMetadataParser defaultVideoFileMetadataParser,
      MovieMetadataProviderResolver movieMetadataProviderResolver,
      MovieService movieService,
      MediaFileRepository mediaFileRepository,
      MutexFactoryProvider mutexFactoryProvider) {
    this.defaultVideoFileMetadataParser = defaultVideoFileMetadataParser;
    this.movieMetadataProviderResolver = movieMetadataProviderResolver;
    this.movieService = movieService;
    this.mediaFileRepository = mediaFileRepository;
    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
  }

  public void process(Library library, MediaFile mediaFile) {
    var mediaInformationResult = parseMediaFileForMovieInfo(mediaFile);

    if (mediaInformationResult.isEmpty()) {
      mediaFile.setStatus(MediaFileStatus.METADATA_PARSING_FAILED);
      mediaFileRepository.save(mediaFile);

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

    var movieSearchResult =
        movieMetadataProviderResolver.search(library, mediaInformationResult.get());

    if (movieSearchResult.isEmpty()) {
      mediaFile.setStatus(MediaFileStatus.METADATA_SEARCH_FAILED);
      mediaFileRepository.save(mediaFile);

      log.error(
          "Failed to find matching search result for MediaFile id: {} at path: '{}'",
          mediaFile.getId(),
          mediaFile.getFilepathUri());

      return;
    }

    log.info(
        "Found metadata search result during enrichment for MediaFile id: {}. Metadata provider: {} and External id: {}",
        mediaFile.getId(),
        movieSearchResult.get().externalSourceType(),
        movieSearchResult.get().externalId());

    enrichMovieMetadata(library, mediaFile, movieSearchResult.get());
  }

  private Optional<VideoFileParserResult> parseMediaFileForMovieInfo(MediaFile mediaFile) {
    var result = defaultVideoFileMetadataParser.parse(mediaFile.getFilename());

    if (result.isEmpty() || StringUtils.isEmpty(result.get().title())) {
      return Optional.empty();
    }

    return result;
  }

  private void enrichMovieMetadata(
      Library library, MediaFile mediaFile, RemoteSearchResult remoteSearchResult) {

    var externalIdMutex = mutexFactory.getMutex(remoteSearchResult.externalId());

    try {
      externalIdMutex.lock();

      updateOrSaveEnrichedMovie(library, mediaFile, remoteSearchResult);
    } catch (Exception ex) {
      log.error("Failure enriching movie metadata:", ex);
    } finally {
      if (externalIdMutex.isHeldByCurrentThread()) {
        externalIdMutex.unlock();
      }
    }
  }

  private void updateOrSaveEnrichedMovie(
      Library library, MediaFile mediaFile, RemoteSearchResult remoteSearchResult) {
    var optionalMovie =
        movieService.addMediaFileToMovieByTmdbId(remoteSearchResult.externalId(), mediaFile);

    if (optionalMovie.isPresent()) {
      markMediaFileAsMatched(mediaFile);
      return;
    }

    var metadataResult = movieMetadataProviderResolver.getMetadata(remoteSearchResult, library);

    if (metadataResult.isEmpty()) {
      return;
    }

    movieService.createMovieWithAssociations(metadataResult.get(), mediaFile);
    markMediaFileAsMatched(mediaFile);
  }

  private void markMediaFileAsMatched(MediaFile mediaFile) {
    mediaFile.setStatus(MediaFileStatus.MATCHED);
    mediaFileRepository.save(mediaFile);
  }
}

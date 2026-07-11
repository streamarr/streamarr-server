package com.streamarr.server.services.metadata;

import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.services.metadata.tmdb.TmdbFindResults;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

@Slf4j
@Component
@RequiredArgsConstructor
public class TmdbSearchDelegate {

  private final TheMovieDatabaseHttpService theMovieDatabaseHttpService;

  @FunctionalInterface
  public interface DirectLookup {
    RemoteSearchResult lookup(String externalId) throws IOException, InterruptedException;
  }

  @FunctionalInterface
  public interface TextSearch<R> {
    List<R> search(VideoFileParserResult videoInfo) throws IOException, InterruptedException;
  }

  public Optional<RemoteSearchResult> search(
      VideoFileParserResult videoInformation,
      Function<TmdbFindResults, Optional<RemoteSearchResult>> findResultExtractor,
      DirectLookup directLookup,
      Function<VideoFileParserResult, Optional<RemoteSearchResult>> textSearch) {
    var findResult = searchByExternalId(videoInformation, findResultExtractor, directLookup);
    if (findResult.isPresent()) {
      return findResult;
    }

    return textSearch.apply(videoInformation);
  }

  public <R> Optional<RemoteSearchResult> searchByText(
      VideoFileParserResult videoInformation,
      TextSearch<R> textSearch,
      Function<R, TmdbSearchResultScorer.CandidateResult> candidateMapper,
      Function<R, RemoteSearchResult> resultMapper) {
    try {
      var results = textSearch.search(videoInformation);

      if (results.isEmpty() && StringUtils.isNotBlank(videoInformation.year())) {
        var withoutYear =
            VideoFileParserResult.builder()
                .title(videoInformation.title())
                .externalId(videoInformation.externalId())
                .externalSource(videoInformation.externalSource())
                .build();
        results = textSearch.search(withoutYear);
      }

      if (results.isEmpty()) {
        return Optional.empty();
      }

      var candidates = results.stream().map(candidateMapper).toList();
      var bestIndex =
          TmdbSearchResultScorer.selectBestMatch(
              videoInformation.title(), videoInformation.year(), candidates);

      if (bestIndex.isEmpty()) {
        return Optional.empty();
      }

      return Optional.of(resultMapper.apply(results.get(bestIndex.getAsInt())));

    } catch (IOException ex) {
      log.error("Failure requesting search results:", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("Search interrupted:", ex);
    }

    return Optional.empty();
  }

  private Optional<RemoteSearchResult> searchByExternalId(
      VideoFileParserResult videoInformation,
      Function<TmdbFindResults, Optional<RemoteSearchResult>> findResultExtractor,
      DirectLookup directLookup) {
    if (StringUtils.isBlank(videoInformation.externalId())
        || videoInformation.externalSource() == null) {
      return Optional.empty();
    }

    if (videoInformation.externalSource() == ExternalSourceType.TMDB) {
      return searchByDirectTmdbId(videoInformation, directLookup);
    }

    var tmdbSource =
        TheMovieDatabaseHttpService.EXTERNAL_SOURCES.get(videoInformation.externalSource());
    if (tmdbSource == null) {
      return Optional.empty();
    }

    try {
      var findResults =
          theMovieDatabaseHttpService.findByExternalId(videoInformation.externalId(), tmdbSource);

      return findResultExtractor.apply(findResults);
    } catch (IOException | JacksonException ex) {
      log.warn(
          "TMDB /find failed for external ID '{}', falling back to text search",
          videoInformation.externalId(),
          ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("TMDB /find interrupted for external ID '{}'", videoInformation.externalId(), ex);
      return Optional.empty();
    }

    return Optional.empty();
  }

  private Optional<RemoteSearchResult> searchByDirectTmdbId(
      VideoFileParserResult videoInformation, DirectLookup directLookup) {
    try {
      return Optional.of(directLookup.lookup(videoInformation.externalId()));
    } catch (IOException | JacksonException ex) {
      log.warn(
          "TMDB direct lookup failed for ID '{}', falling back to text search",
          videoInformation.externalId(),
          ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("TMDB direct lookup interrupted for ID '{}'", videoInformation.externalId(), ex);
      return Optional.empty();
    }

    return Optional.empty();
  }
}

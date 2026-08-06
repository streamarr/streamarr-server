package com.streamarr.server.services.metadata;

import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.Found;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.NotFound;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.TemporarilyUnavailable;
import com.streamarr.server.services.metadata.tmdb.TmdbApiException;
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

  public MetadataSearchOutcome search(
      VideoFileParserResult videoInformation,
      Function<TmdbFindResults, Optional<RemoteSearchResult>> findResultExtractor,
      DirectLookup directLookup,
      Function<VideoFileParserResult, MetadataSearchOutcome> textSearch) {
    var findResult = searchByExternalId(videoInformation, findResultExtractor, directLookup);
    if (findResult instanceof Found) {
      return findResult;
    }

    if (findResult instanceof TemporarilyUnavailable(var cause)
        && cause instanceof InterruptedException) {
      return findResult;
    }

    var textResult = textSearch.apply(videoInformation);
    if (!(textResult instanceof NotFound)) {
      return textResult;
    }

    if (findResult instanceof TemporarilyUnavailable) {
      return findResult;
    }

    return textResult;
  }

  public <R> MetadataSearchOutcome searchByText(
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
        return new NotFound();
      }

      var candidates = results.stream().map(candidateMapper).toList();
      var bestIndex =
          TmdbSearchResultScorer.selectBestMatch(
              videoInformation.title(), videoInformation.year(), candidates);

      if (bestIndex.isEmpty()) {
        return new NotFound();
      }

      return new Found(resultMapper.apply(results.get(bestIndex.getAsInt())));

    } catch (IOException ex) {
      log.error("Failure requesting search results:", ex);
      return new TemporarilyUnavailable(ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("Search interrupted:", ex);
      return new TemporarilyUnavailable(ex);
    }
  }

  private MetadataSearchOutcome searchByExternalId(
      VideoFileParserResult videoInformation,
      Function<TmdbFindResults, Optional<RemoteSearchResult>> findResultExtractor,
      DirectLookup directLookup) {
    if (StringUtils.isBlank(videoInformation.externalId())
        || videoInformation.externalSource() == null) {
      return new NotFound();
    }

    if (videoInformation.externalSource() == ExternalSourceType.TMDB) {
      return searchByDirectTmdbId(videoInformation, directLookup);
    }

    var tmdbSource =
        TheMovieDatabaseHttpService.EXTERNAL_SOURCES.get(videoInformation.externalSource());
    if (tmdbSource == null) {
      return new NotFound();
    }

    try {
      var findResults =
          theMovieDatabaseHttpService.findByExternalId(videoInformation.externalId(), tmdbSource);

      return findResultExtractor
          .apply(findResults)
          .<MetadataSearchOutcome>map(Found::new)
          .orElseGet(NotFound::new);
    } catch (IOException | JacksonException ex) {
      log.warn(
          "TMDB /find failed for external ID '{}', falling back to text search",
          videoInformation.externalId(),
          ex);
      return new TemporarilyUnavailable(ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("TMDB /find interrupted for external ID '{}'", videoInformation.externalId(), ex);
      return new TemporarilyUnavailable(ex);
    }
  }

  private MetadataSearchOutcome searchByDirectTmdbId(
      VideoFileParserResult videoInformation, DirectLookup directLookup) {
    try {
      return new Found(directLookup.lookup(videoInformation.externalId()));
    } catch (TmdbApiException ex) {
      if (ex.getStatusCode() == 404) {
        return new NotFound();
      }

      log.warn(
          "TMDB direct lookup failed for ID '{}', falling back to text search",
          videoInformation.externalId(),
          ex);
      return new TemporarilyUnavailable(ex);
    } catch (IOException | JacksonException ex) {
      log.warn(
          "TMDB direct lookup failed for ID '{}', falling back to text search",
          videoInformation.externalId(),
          ex);
      return new TemporarilyUnavailable(ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("TMDB direct lookup interrupted for ID '{}'", videoInformation.externalId(), ex);
      return new TemporarilyUnavailable(ex);
    }
  }
}

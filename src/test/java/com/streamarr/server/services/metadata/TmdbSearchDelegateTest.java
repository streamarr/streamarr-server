package com.streamarr.server.services.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.fakes.FakeTmdbHttpService;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.Found;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.NotFound;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.TemporarilyUnavailable;
import com.streamarr.server.services.metadata.tmdb.TmdbApiException;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("TMDB Search Delegate Tests")
class TmdbSearchDelegateTest {

  private final TmdbSearchDelegate searchDelegate =
      new TmdbSearchDelegate(new FakeTmdbHttpService());

  @Test
  @DisplayName("Should preserve unavailable outcome when fallback text search finds no match")
  void shouldPreserveUnavailableOutcomeWhenFallbackTextSearchFindsNoMatch() {
    var directLookupFailure = new IOException("TMDB direct lookup timed out");
    var videoInformation =
        VideoFileParserResult.builder()
            .title("Missing Movie")
            .externalId("99999")
            .externalSource(ExternalSourceType.TMDB)
            .build();

    var outcome =
        searchDelegate.search(
            videoInformation,
            _ -> Optional.empty(),
            _ -> {
              throw directLookupFailure;
            },
            _ -> new NotFound());

    assertThat(outcome)
        .isInstanceOfSatisfying(
            TemporarilyUnavailable.class,
            unavailable -> assertThat(unavailable.cause()).isSameAs(directLookupFailure));
  }

  @Test
  @DisplayName("Should skip fallback text search when direct lookup is interrupted")
  void shouldSkipFallbackTextSearchWhenDirectLookupIsInterrupted() {
    var interruption = new InterruptedException("TMDB direct lookup interrupted");
    var textSearch = new RecordingTextSearch(new NotFound());
    var videoInformation =
        VideoFileParserResult.builder()
            .title("Interrupted Movie")
            .externalId("99999")
            .externalSource(ExternalSourceType.TMDB)
            .build();

    try {
      var outcome =
          searchDelegate.search(
              videoInformation,
              _ -> Optional.empty(),
              _ -> {
                throw interruption;
              },
              textSearch);

      assertThat(outcome)
          .isInstanceOfSatisfying(
              TemporarilyUnavailable.class,
              unavailable -> assertThat(unavailable.cause()).isSameAs(interruption));
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(textSearch.inputs()).isEmpty();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("Should return text search result when direct TMDB ID does not exist")
  void shouldReturnTextSearchResultWhenDirectTmdbIdDoesNotExist() {
    var fallbackResult =
        RemoteSearchResult.builder()
            .title("Recovered Movie")
            .externalId("12345")
            .externalSourceType(ExternalSourceType.TMDB)
            .build();
    var videoInformation =
        VideoFileParserResult.builder()
            .title("Missing Movie")
            .externalId("99999")
            .externalSource(ExternalSourceType.TMDB)
            .build();

    var outcome =
        searchDelegate.search(
            videoInformation,
            _ -> Optional.empty(),
            _ -> {
              throw new TmdbApiException(404, "Movie not found");
            },
            _ -> new Found(fallbackResult));

    assertThat(outcome)
        .isInstanceOfSatisfying(
            Found.class, found -> assertThat(found.result()).isSameAs(fallbackResult));
  }

  @Test
  @DisplayName(
      "Should return not found when direct TMDB ID does not exist and fallback finds no match")
  void shouldReturnNotFoundWhenDirectTmdbIdDoesNotExistAndFallbackFindsNoMatch() {
    var videoInformation =
        VideoFileParserResult.builder()
            .title("Missing Movie")
            .externalId("99999")
            .externalSource(ExternalSourceType.TMDB)
            .build();

    var outcome =
        searchDelegate.search(
            videoInformation,
            _ -> Optional.empty(),
            _ -> {
              throw new TmdbApiException(404, "Movie not found");
            },
            _ -> new NotFound());

    assertThat(outcome).isInstanceOf(NotFound.class);
  }

  private static final class RecordingTextSearch
      implements Function<VideoFileParserResult, MetadataSearchOutcome> {

    private final MetadataSearchOutcome outcome;
    private final List<VideoFileParserResult> inputs = new ArrayList<>();

    private RecordingTextSearch(MetadataSearchOutcome outcome) {
      this.outcome = outcome;
    }

    @Override
    public MetadataSearchOutcome apply(VideoFileParserResult input) {
      inputs.add(input);
      return outcome;
    }

    private List<VideoFileParserResult> inputs() {
      return List.copyOf(inputs);
    }
  }
}

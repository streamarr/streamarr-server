package com.streamarr.server.services.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.fakes.FakeTmdbHttpService;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.NotFound;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.TemporarilyUnavailable;
import com.streamarr.server.services.metadata.tmdb.TmdbApiException;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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
              _ -> {
                throw new AssertionError("Fallback text search must not run after interruption");
              });

      assertThat(outcome)
          .isInstanceOfSatisfying(
              TemporarilyUnavailable.class,
              unavailable -> assertThat(unavailable.cause()).isSameAs(interruption));
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName(
      "Should return not found when direct TMDB ID does not exist and fallback finds no match")
  void shouldReturnNotFoundWhenDirectTmdbIdDoesNotExistAndFallbackFindsNoMatch() {
    var textSearchAttempted = new AtomicBoolean();
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
            _ -> {
              textSearchAttempted.set(true);
              return new NotFound();
            });

    assertThat(textSearchAttempted).isTrue();
    assertThat(outcome).isInstanceOf(NotFound.class);
  }
}

package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.streaming.Chapter;
import com.streamarr.server.domain.streaming.ChapterKnowledge;
import com.streamarr.server.fakes.FakeAuthorizationDecider;
import com.streamarr.server.graphql.dataloaders.ChapterDataLoader;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.support.security.WithProfileContext;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@WithProfileContext
@SpringBootTest(
    classes = {
      MovieResolver.class,
      MediaFileFieldResolver.class,
      ChapterDataLoader.class,
      SecurityContextAuthorizationService.class,
      FakeAuthorizationDecider.class
    })
@DisplayName("Media File Field Resolver Tests")
class MediaFileFieldResolverTest {

  private static final String FILES_QUERY =
      """
      query ($id: ID!) {
        movie(id: $id) {
          files {
            filename
            chapterStatus
            chapters { startSeconds endSeconds title }
          }
        }
      }
      """;

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private UserAccountRepository userAccountRepository;
  @MockitoBean private MovieService movieService;
  @MockitoBean private PersistedProbeReader persistedProbeReader;

  @Test
  @DisplayName("Should read the chapters of every file in one batch when a movie lists its files")
  void shouldReadTheChaptersOfEveryFileInOneBatchWhenAMovieListsItsFiles() {
    var movie = movieWithFiles("listed.mkv", "omitted.mkv", "unknown.mkv");
    var files = movieService.findMediaFiles(movie.getId());
    var listed =
        new ChapterKnowledge.Known(
            List.of(
                Chapter.builder()
                    .startTime(Optional.of(Duration.ofSeconds(-1, 500_000_000)))
                    .title(Optional.of("Prologue"))
                    .build(),
                Chapter.builder().endTime(Optional.of(Duration.ofSeconds(90))).build()));
    // Only a read of all three files answers, so a read per file would leave every status UNKNOWN.
    when(persistedProbeReader.findChapters(
            Set.of(files.get(0).getId(), files.get(1).getId(), files.get(2).getId())))
        .thenReturn(
            Map.of(
                files.get(0).getId(), listed,
                files.get(1).getId(), new ChapterKnowledge.Omitted(),
                files.get(2).getId(), new ChapterKnowledge.NotReported()));

    List<Map<String, Object>> result =
        dgsQueryExecutor.executeAndExtractJsonPath(
            FILES_QUERY, "data.movie.files", Map.of("id", movie.getId().toString()));

    assertThat(result)
        .containsExactly(
            Map.of(
                "filename",
                "listed.mkv",
                "chapterStatus",
                "KNOWN",
                "chapters",
                List.of(chapterData(-0.5, null, "Prologue"), chapterData(null, 90.0, null))),
            Map.of("filename", "omitted.mkv", "chapterStatus", "OMITTED", "chapters", List.of()),
            Map.of("filename", "unknown.mkv", "chapterStatus", "UNKNOWN", "chapters", List.of()));
  }

  private Movie movieWithFiles(String... filenames) {
    var movie = Movie.builder().title("Chapters").build();
    movie.setId(UUID.randomUUID());
    var files =
        List.of(filenames).stream()
            .map(
                filename -> {
                  var file = MediaFile.builder().filename(filename).build();
                  file.setId(UUID.randomUUID());
                  return file;
                })
            .toList();
    when(movieService.findById(movie.getId())).thenReturn(Optional.of(movie));
    when(movieService.findMediaFiles(movie.getId())).thenReturn(files);
    return movie;
  }

  private static Map<String, Object> chapterData(Double start, Double end, String title) {
    var data = new HashMap<String, Object>();
    data.put("startSeconds", start);
    data.put("endSeconds", end);
    data.put("title", title);
    return data;
  }
}

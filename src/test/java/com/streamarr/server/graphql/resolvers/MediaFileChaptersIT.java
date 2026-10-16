package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.Chapter;
import com.streamarr.server.domain.streaming.ChapterKnowledge;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.support.security.WithProfileContext;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@EnableDgsTest
@WithProfileContext
@DisplayName("Media File Chapters Integration Tests")
class MediaFileChaptersIT extends AbstractIntegrationTest {

  private static final SourceFileSnapshot SNAPSHOT =
      new SourceFileSnapshot(4096, Instant.parse("2026-10-16T10:00:00Z"));
  private static final int PROBE_VERSION_WITH_CHAPTERS = 4;

  @Autowired private DgsQueryExecutor dgsQueryExecutor;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private MediaFileContainerInfoRepository containerInfoRepository;

  @Test
  @DisplayName("Should return each file's stored chapters in stored order when a movie is queried")
  void shouldReturnEachFilesStoredChaptersInStoredOrderWhenMovieQueried() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie =
        movieRepository.saveAndFlush(Movie.builder().title("Chapters").library(library).build());
    var titled = createMediaFile(movie, "titled.mkv");
    var withoutChapters = createMediaFile(movie, "without-chapters.mp4");
    var omitted = createMediaFile(movie, "omitted.mkv");
    var unprobed = createMediaFile(movie, "unprobed.mkv");
    publish(
        titled,
        new ChapterKnowledge.Known(
            List.of(
                chapter(Duration.ofMillis(-41), Duration.ofMillis(300_500), "Opening Credits"),
                chapter(Duration.ofMillis(1_500_250), Duration.ofMillis(2_400_000), null),
                chapter(Duration.ofMillis(300_500), Duration.ofMillis(1_500_250), "Middle"))));
    publish(withoutChapters, new ChapterKnowledge.Known(List.of()));
    publish(omitted, new ChapterKnowledge.Omitted());

    var files = queryFiles(movie);

    assertThat(files.get(titled.getFilename()))
        .containsEntry("chapterStatus", "KNOWN")
        .containsEntry(
            "chapters",
            List.of(
                chapterData(-0.041, 300.5, "Opening Credits"),
                chapterData(1500.25, 2400.0, null),
                chapterData(300.5, 1500.25, "Middle")));
    assertThat(files.get(withoutChapters.getFilename()))
        .containsEntry("chapterStatus", "KNOWN")
        .containsEntry("chapters", List.of());
    assertThat(files.get(omitted.getFilename()))
        .containsEntry("chapterStatus", "OMITTED")
        .containsEntry("chapters", List.of());
    assertThat(files.get(unprobed.getFilename()))
        .containsEntry("chapterStatus", "UNKNOWN")
        .containsEntry("chapters", List.of());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Map<String, Object>> queryFiles(Movie movie) {
    var result =
        dgsQueryExecutor.execute(
            """
            {
              movie(id: "%s") {
                files {
                  filename
                  chapterStatus
                  chapters { startSeconds endSeconds title }
                }
              }
            }
            """
                .formatted(movie.getId()));

    assertThat(result.getErrors()).isEmpty();
    Map<String, Object> data = result.getData();
    var files = (List<Map<String, Object>>) ((Map<String, Object>) data.get("movie")).get("files");
    var filesByName = new HashMap<String, Map<String, Object>>();
    files.forEach(file -> filesByName.put((String) file.get("filename"), file));
    return filesByName;
  }

  private MediaFile createMediaFile(Movie movie, String filename) {
    return mediaFileRepository.saveAndFlush(
        MediaFile.builder()
            .mediaId(movie.getId())
            .libraryId(movie.getLibrary().getId())
            .status(MediaFileStatus.MATCHED)
            .filename(filename)
            .filepathUri("file:///media/" + UUID.randomUUID() + "/" + filename)
            .build());
  }

  private void publish(MediaFile file, ChapterKnowledge chapters) {
    var outcome =
        new ProbeOutcome.Success(
            ProbeContainer.builder().format(Optional.of("matroska")).chapters(chapters).build(),
            List.of(StreamInfo.builder().codecType("video").codec(Optional.of("h264")).build()),
            new KeyframeKnowledge.NotReported());
    assertThat(
            containerInfoRepository.publish(
                ProbePublication.builder()
                    .mediaFileId(file.getId())
                    .snapshot(SNAPSHOT)
                    .probeVersion(PROBE_VERSION_WITH_CHAPTERS)
                    .outcome(outcome)
                    .build()))
        .isTrue();
  }

  private static Chapter chapter(Duration startTime, Duration endTime, String title) {
    return Chapter.builder()
        .startTime(Optional.of(startTime))
        .endTime(Optional.of(endTime))
        .title(Optional.ofNullable(title))
        .build();
  }

  private static Map<String, Object> chapterData(double start, double end, String title) {
    var data = new HashMap<String, Object>();
    data.put("startSeconds", start);
    data.put("endSeconds", end);
    data.put("title", title);
    return data;
  }
}

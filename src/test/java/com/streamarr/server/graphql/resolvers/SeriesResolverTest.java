package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(classes = {SeriesResolver.class})
@DisplayName("Series Resolver Tests")
class SeriesResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private SeriesRepository seriesRepository;

  @MockitoBean private MediaFileRepository mediaFileRepository;

  @Test
  @DisplayName("Should return series when valid ID provided")
  void shouldReturnSeriesWhenValidIdProvided() {
    var seriesId = UUID.randomUUID();
    var series = Series.builder().title("Breaking Bad").tagline("All Hail the King").build();
    series.setId(seriesId);

    when(seriesRepository.findById(seriesId)).thenReturn(Optional.of(series));

    String title =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ series(id: \"%s\") { title tagline } }", seriesId),
            "data.series.title");

    assertThat(title).isEqualTo("Breaking Bad");
  }

  @Test
  @DisplayName("Should return null when series not found")
  void shouldReturnNullWhenSeriesNotFound() {
    when(seriesRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

    Object result =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ series(id: \"%s\") { title } }", UUID.randomUUID()), "data.series");

    assertThat(result).isNull();
  }

  @Test
  @DisplayName("Should return error when invalid ID provided")
  void shouldReturnErrorWhenInvalidIdProvided() {
    var result = dgsQueryExecutor.execute("{ series(id: \"not-a-uuid\") { title } }");

    assertThat(result.getErrors()).isNotEmpty();
    assertThat(result.getErrors().get(0).getMessage()).contains("Invalid ID format");
  }

  @Test
  @DisplayName("Should return files for series")
  void shouldReturnFilesForSeries() {
    var seriesId = UUID.randomUUID();
    var series = Series.builder().title("Breaking Bad").build();
    series.setId(seriesId);

    var mediaFile =
        MediaFile.builder()
            .filename("breaking.bad.s01e01.mkv")
            .filepathUri("/media/shows/Breaking Bad/Season 1/breaking.bad.s01e01.mkv")
            .size(1500000000L)
            .build();
    mediaFile.setId(UUID.randomUUID());

    when(seriesRepository.findById(seriesId)).thenReturn(Optional.of(series));
    when(mediaFileRepository.findByMediaId(seriesId)).thenReturn(List.of(mediaFile));

    String filepathUri =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ series(id: \"%s\") { files { filepathUri } } }", seriesId),
            "data.series.files[0].filepathUri");

    assertThat(filepathUri)
        .isEqualTo("/media/shows/Breaking Bad/Season 1/breaking.bad.s01e01.mkv");
  }
}

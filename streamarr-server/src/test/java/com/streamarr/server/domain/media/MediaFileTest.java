package com.streamarr.server.domain.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.metadata.Genre;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Media File Entity Tests")
class MediaFileTest {

  @Test
  @DisplayName("Should be equal when both have the same filepath URI")
  void shouldBeEqualWhenBothHaveSameFilepathUri() {
    var a = MediaFile.builder().filepathUri("file:///media/movie.mkv").filename("a.mkv").build();
    var b = MediaFile.builder().filepathUri("file:///media/movie.mkv").filename("b.mkv").build();

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
  }

  @Test
  @DisplayName("Should not be equal when filepath URIs differ")
  void shouldNotBeEqualWhenFilepathUrisDiffer() {
    var a = MediaFile.builder().filepathUri("file:///media/movie.mkv").build();
    var b = MediaFile.builder().filepathUri("file:///media/other.mkv").build();

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  @DisplayName("Should not be equal when filepath URI is null on either side")
  void shouldNotBeEqualWhenFilepathUriIsNull() {
    var a = MediaFile.builder().filename("orphan.mkv").build();
    var b = MediaFile.builder().filename("orphan.mkv").build();
    var withUri = MediaFile.builder().filepathUri("file:///media/movie.mkv").build();

    assertThat(a).isNotEqualTo(b).isNotEqualTo(withUri);
    assertThat(withUri).isNotEqualTo(a);
  }

  @Test
  @DisplayName("Should not be equal when compared against different type")
  void shouldNotBeEqualWhenComparedAgainstDifferentType() {
    var mediaFile = MediaFile.builder().filepathUri("file:///media/movie.mkv").build();

    assertThat(mediaFile).isNotEqualTo(Genre.builder().name("Drama").sourceId("18").build());
  }
}

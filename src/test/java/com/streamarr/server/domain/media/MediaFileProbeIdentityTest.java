package com.streamarr.server.domain.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Media file probe entity identity")
class MediaFileProbeIdentityTest {

  @Test
  @DisplayName("Should identify container outcomes by media file when probe properties differ")
  void shouldIdentifyContainerOutcomesByMediaFileWhenProbePropertiesDiffer() {
    var mediaFileId = UUID.randomUUID();
    var original =
        MediaFileContainerInfo.builder().mediaFileId(mediaFileId).probeVersion(1).build();
    var refreshed =
        MediaFileContainerInfo.builder().mediaFileId(mediaFileId).probeVersion(2).build();

    assertThat(original).isEqualTo(refreshed);
    assertThat(refreshed).isEqualTo(original);
    assertThat(original.hashCode()).isEqualTo(MediaFileContainerInfo.class.hashCode());
    assertThat(refreshed).hasSameHashCodeAs(original);
  }

  @Test
  @DisplayName(
      "Should identify streams by media file and stream index when probe properties differ")
  void shouldIdentifyStreamsByMediaFileAndStreamIndexWhenProbePropertiesDiffer() {
    var mediaFileId = UUID.randomUUID();
    var original =
        MediaFileStreamInfo.builder()
            .id(new MediaFileStreamId(mediaFileId, 1))
            .codec("h264")
            .build();
    var refreshed =
        MediaFileStreamInfo.builder()
            .id(new MediaFileStreamId(mediaFileId, 1))
            .codec("hevc")
            .build();

    assertThat(original).isEqualTo(refreshed);
    assertThat(refreshed).isEqualTo(original);
    assertThat(original.hashCode()).isEqualTo(MediaFileStreamInfo.class.hashCode());
    assertThat(refreshed).hasSameHashCodeAs(original);
    assertThat(original)
        .isNotEqualTo(
            MediaFileStreamInfo.builder().id(new MediaFileStreamId(mediaFileId, 2)).build())
        .isNotEqualTo(
            MediaFileStreamInfo.builder().id(new MediaFileStreamId(UUID.randomUUID(), 1)).build())
        .isNotEqualTo(null)
        .isNotEqualTo(mediaFileId);
    var transientStream = MediaFileStreamInfo.builder().build();
    assertThat(transientStream).isEqualTo(transientStream);
    assertThat(transientStream).isNotEqualTo(MediaFileStreamInfo.builder().build());
    assertThat(transientStream).isNotEqualTo(original);
    assertThat(original).isNotEqualTo(transientStream);
  }

  @Test
  @DisplayName(
      "Should distinguish containers when their media file identities differ or are absent")
  void shouldDistinguishContainersWhenTheirMediaFileIdentitiesDifferOrAreAbsent() {
    var mediaFileId = UUID.randomUUID();
    var persisted = MediaFileContainerInfo.builder().mediaFileId(mediaFileId).build();
    var transientContainer = MediaFileContainerInfo.builder().build();

    assertThat(persisted)
        .isNotEqualTo(MediaFileContainerInfo.builder().mediaFileId(UUID.randomUUID()).build())
        .isNotEqualTo(null)
        .isNotEqualTo(mediaFileId)
        .isNotEqualTo(transientContainer);
    assertThat(transientContainer).isEqualTo(transientContainer);
    assertThat(transientContainer).isNotEqualTo(MediaFileContainerInfo.builder().build());
    assertThat(transientContainer).isNotEqualTo(persisted);
  }
}

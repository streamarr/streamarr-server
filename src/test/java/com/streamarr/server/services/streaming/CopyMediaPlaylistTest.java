package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.StreamSessionFixture;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Copy Media Playlist Tests")
class CopyMediaPlaylistTest {

  private static final StreamingProperties PROPERTIES =
      StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build();

  // Keyframes every 4 s open stream copy segments at 0, 8 and 12 s of a 14 s source.
  @Test
  @DisplayName(
      "Should list the stream copy's segments and their EXTINF total when keyframes open every"
          + " segment")
  void shouldListStreamCopysSegmentsAndExtinfTotalWhenKeyframesOpenEverySegment() {
    var source =
        StreamSessionFixture.defaultProbeBuilder()
            .duration(Duration.ofSeconds(14))
            .keyframes(ProbeFixture.keyframesEvery(Duration.ofSeconds(4), Duration.ofSeconds(14)))
            .build();

    var playlist = CopyMediaPlaylist.of(source, PROPERTIES);

    assertThat(playlist.segmentCount()).isEqualTo(3);
    assertThat(playlist.durationSeconds()).isEqualByComparingTo(new BigDecimal("14"));
  }

  @Test
  @DisplayName("Should list no media playlist when the source's keyframes are not known")
  void shouldListNoMediaPlaylistWhenSourcesKeyframesAreNotKnown() {
    var source =
        StreamSessionFixture.defaultProbeBuilder()
            .keyframes(new KeyframeKnowledge.NotReported())
            .build();

    assertThat(CopyMediaPlaylist.of(source, PROPERTIES)).isEqualTo(CopyMediaPlaylist.UNKNOWN);
  }
}

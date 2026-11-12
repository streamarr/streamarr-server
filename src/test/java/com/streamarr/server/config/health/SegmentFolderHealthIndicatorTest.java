package com.streamarr.server.config.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome.Deleted;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome.NoOwnedFolder;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome.Refused;
import com.streamarr.server.services.streaming.local.SegmentFolderRefusal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

@Tag("UnitTest")
@DisplayName("Segment Folder Health Indicator Tests")
class SegmentFolderHealthIndicatorTest {

  @Test
  @DisplayName("Should report degraded with the refusal when the start refused the segment folder")
  void shouldReportDegradedWithTheRefusalWhenTheStartRefusedTheSegmentFolder() {
    var health = healthAfter(new Refused(SegmentFolderRefusal.MEDIA_FOLDER));

    assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
    assertThat(health.getDetails()).containsEntry("refusal", "media_folder");
  }

  @Test
  @DisplayName("Should report degraded with the count when leftovers remain after the start")
  void shouldReportDegradedWithTheCountWhenLeftoversRemainAfterTheStart() {
    var health = healthAfter(new Deleted(3, 2));

    assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
    assertThat(health.getDetails()).containsEntry("remaining", 2);
  }

  @Test
  @DisplayName("Should report up when the start deleted every leftover")
  void shouldReportUpWhenTheStartDeletedEveryLeftover() {
    var health = healthAfter(new Deleted(3, 0));

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("Should report up when the server shares the default segment folder")
  void shouldReportUpWhenTheServerSharesTheDefaultSegmentFolder() {
    var health = healthAfter(new NoOwnedFolder());

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  private static Health healthAfter(SegmentFolderLeftoversOutcome outcome) {
    return new SegmentFolderHealthIndicator(outcome).health();
  }
}

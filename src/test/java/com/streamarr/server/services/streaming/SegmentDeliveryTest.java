package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Segment Delivery Tests")
class SegmentDeliveryTest {

  @Test
  @DisplayName("Should compare ready deliveries by segment content")
  void shouldCompareReadyDeliveriesBySegmentContent() {
    var delivery = new SegmentDelivery.Ready(new byte[] {0x47, 0x00});
    var sameContent = new SegmentDelivery.Ready(new byte[] {0x47, 0x00});
    var differentContent = new SegmentDelivery.Ready(new byte[] {0x47, 0x01});

    assertThat(delivery).isEqualTo(sameContent).hasSameHashCodeAs(sameContent);
    assertThat(delivery).isNotEqualTo(differentContent);
    assertThat(delivery).isNotEqualTo(new SegmentDelivery.SessionEnded());
  }

  @Test
  @DisplayName("Should describe ready deliveries by size instead of dumping media bytes")
  void shouldDescribeReadyDeliveriesBySizeInsteadOfDumpingMediaBytes() {
    var delivery = new SegmentDelivery.Ready(new byte[] {0x47, 0x00, 0x11});

    assertThat(delivery).hasToString("Ready[3 bytes]");
  }
}

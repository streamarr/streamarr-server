package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Streaming Configuration Tests")
class StreamingConfigTest {

  @Test
  @DisplayName("Should retain local worker identity and create a new boot identity per context")
  void shouldRetainLocalWorkerIdentityAndCreateNewBootIdentityPerContext() {
    var config = new StreamingConfig();

    var firstBoot = config.localWorkerTarget();
    var secondBoot = config.localWorkerTarget();

    assertThat(firstBoot.workerId()).isEqualTo(StreamingConfig.LOCAL_WORKER_ID);
    assertThat(secondBoot.workerId()).isEqualTo(firstBoot.workerId());
    assertThat(secondBoot.bootId()).isNotEqualTo(firstBoot.bootId());
  }
}

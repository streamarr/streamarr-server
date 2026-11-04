package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.BitstreamRewrite;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Remote Bitstream Rewrite Mapper Tests")
class RemoteBitstreamRewriteMapperTest {

  // A job lists a rewrite and a worker lists the same contract value, so each domain rewrite must
  // come back from the value that it is sent as.
  @ParameterizedTest
  @EnumSource(BitstreamRewrite.class)
  @DisplayName("Should read back each rewrite when it goes through the worker contract")
  void shouldReadBackEachRewriteWhenItGoesThroughTheWorkerContract(BitstreamRewrite rewrite) {
    var value = RemoteBitstreamRewriteMapper.toProto(rewrite).getNumber();

    assertThat(RemoteBitstreamRewriteMapper.fromProto(value)).contains(rewrite);
  }

  // streamarr/streamarr-server#651 defines the profile 7 conversion, so no decision lists it yet.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest
  @EnumSource(
      value = com.streamarr.transcode.v1.BitstreamRewrite.class,
      names = {
        "BITSTREAM_REWRITE_UNSPECIFIED",
        "BITSTREAM_REWRITE_CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1"
      })
  @DisplayName("Should name no rewrite when a worker lists a value that the server does not plan")
  void shouldNameNoRewriteWhenAWorkerListsAValueThatTheServerDoesNotPlan(
      com.streamarr.transcode.v1.BitstreamRewrite listed) {
    assertThat(RemoteBitstreamRewriteMapper.fromProto(listed.getNumber())).isEmpty();
  }

  // A newer worker can list a value that this server's contract does not know.
  @Test
  @DisplayName("Should name no rewrite when a worker lists a value that the contract does not know")
  void shouldNameNoRewriteWhenAWorkerListsAValueThatTheContractDoesNotKnow() {
    assertThat(RemoteBitstreamRewriteMapper.fromProto(99)).isEmpty();
  }
}

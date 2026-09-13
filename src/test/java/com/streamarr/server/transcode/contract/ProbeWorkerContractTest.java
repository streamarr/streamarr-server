package com.streamarr.server.transcode.contract;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Duration;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeContainerInfo;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.v1.StartProbeCommand;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe Worker Contract Tests")
class ProbeWorkerContractTest {

  @Test
  @DisplayName("Should retain supported probe versions when serializing worker capabilities")
  void shouldRetainSupportedProbeVersionsWhenSerializingWorkerCapabilities() throws Exception {
    var capabilities =
        WorkerCapabilities.newBuilder().addProbeVersions(1).addProbeVersions(2).build();

    var decoded = WorkerCapabilities.parseFrom(capabilities.toByteArray());

    assertThat(decoded.getProbeVersionsList()).containsExactly(1, 2);
    assertThat(WorkerCapabilities.getDefaultInstance().getProbeVersionsList()).isEmpty();
  }

  @Test
  @DisplayName("Should retain probe attempt version and opaque source when sending a probe command")
  void shouldRetainProbeAttemptVersionAndOpaqueSourceWhenSendingProbeCommand() throws Exception {
    var attemptId = toProto(UUID.randomUUID());
    var source =
        MediaSourceRef.newBuilder()
            .setSourceNamespaceId(toProto(UUID.randomUUID()))
            .setRelativeKey("movies/feature%2Ffilm.mkv")
            .build();
    var worker =
        WorkerIdentity.newBuilder()
            .setWorkerId(toProto(UUID.randomUUID()))
            .setBootId(toProto(UUID.randomUUID()))
            .build();
    var command =
        EstablishWorkerSessionResponse.newBuilder()
            .setStartProbe(
                StartProbeCommand.newBuilder()
                    .setTarget(worker)
                    .setRequest(
                        ProbeRequest.newBuilder()
                            .setProbeAttemptId(attemptId)
                            .setProbeVersion(2)
                            .setSource(source)))
            .build();

    var decoded = EstablishWorkerSessionResponse.parseFrom(command.toByteArray()).getStartProbe();

    assertThat(decoded.getTarget()).isEqualTo(worker);
    assertThat(decoded.getRequest().getProbeAttemptId()).isEqualTo(attemptId);
    assertThat(decoded.getRequest().getProbeVersion()).isEqualTo(2);
    assertThat(decoded.getRequest().getSource()).isEqualTo(source);
  }

  @Test
  @DisplayName("Should preserve complete ordered stream properties when returning a probe result")
  void shouldPreserveCompleteOrderedStreamPropertiesWhenReturningProbeResult() throws Exception {
    var media =
        ProbeMediaInfo.newBuilder()
            .setContainer(
                ProbeContainerInfo.newBuilder()
                    .setFormat("matroska,webm")
                    .setDuration(Duration.newBuilder().setSeconds(125).setNanos(123456789))
                    .setBitrateBitsPerSecond(9_000_000))
            .addStreams(
                ProbeStreamInfo.newBuilder()
                    .setIndex(2)
                    .setCodecType("video")
                    .setCodec("h264")
                    .setWidth(1920)
                    .setHeight(1080)
                    .setFramerate(24000.0 / 1001)
                    .setBitrateBitsPerSecond(8_000_000))
            .addStreams(
                ProbeStreamInfo.newBuilder()
                    .setIndex(4)
                    .setCodecType("audio")
                    .setCodec("aac")
                    .setChannels(6)
                    .setLanguage("eng")
                    .setBitrateBitsPerSecond(512_000)
                    .setIsDefault(true))
            .addStreams(
                ProbeStreamInfo.newBuilder()
                    .setIndex(9)
                    .setCodecType("subtitle")
                    .setCodec("subrip")
                    .setLanguage("fra")
                    .setIsForced(true))
            .addStreams(ProbeStreamInfo.newBuilder().setIndex(11).setCodecType("unknown"))
            .build();
    var result =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(toProto(UUID.randomUUID()))
            .setProbeVersion(2)
            .setMedia(media)
            .build();
    var event = EstablishWorkerSessionRequest.newBuilder().setProbeResult(result).build();

    var decoded = EstablishWorkerSessionRequest.parseFrom(event.toByteArray()).getProbeResult();

    assertThat(decoded).isEqualTo(result);
    assertThat(decoded.getMedia().getStreamsList())
        .extracting(ProbeStreamInfo::getIndex)
        .containsExactly(2, 4, 9, 11);
    var unknown = decoded.getMedia().getStreams(3);
    assertThat(unknown.hasCodec()).isFalse();
    assertThat(unknown.hasLanguage()).isFalse();
    assertThat(unknown.hasChannels()).isFalse();
    assertThat(unknown.hasBitrateBitsPerSecond()).isFalse();
    assertThat(unknown.hasWidth()).isFalse();
    assertThat(unknown.hasHeight()).isFalse();
    assertThat(unknown.hasFramerate()).isFalse();
  }

  @Test
  @DisplayName("Should preserve a typed media failure when returning an unsuccessful probe")
  void shouldPreserveTypedMediaFailureWhenReturningUnsuccessfulProbe() throws Exception {
    var result =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(toProto(UUID.randomUUID()))
            .setProbeVersion(1)
            .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
            .build();

    var decoded = ProbeAttemptResult.parseFrom(result.toByteArray());

    assertThat(decoded).isEqualTo(result);
    assertThat(decoded.hasMedia()).isFalse();
    assertThat(decoded.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA);
  }
}

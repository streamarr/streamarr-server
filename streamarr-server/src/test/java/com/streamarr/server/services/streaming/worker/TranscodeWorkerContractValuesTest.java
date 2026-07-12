package com.streamarr.server.services.streaming.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.transcode.engine.model.AudioDecision;
import com.streamarr.transcode.engine.model.ContainerFormat;
import com.streamarr.transcode.engine.model.MediaSourceRef;
import com.streamarr.transcode.engine.model.RenditionObservation;
import com.streamarr.transcode.engine.model.RenditionSpec;
import com.streamarr.transcode.engine.model.RenditionState;
import com.streamarr.transcode.engine.model.SubtitleDecision;
import com.streamarr.transcode.engine.model.TranscodeDecision;
import com.streamarr.transcode.engine.model.TranscodeExecutionParameters;
import com.streamarr.transcode.engine.model.TranscodeJobObservation;
import com.streamarr.transcode.engine.model.TranscodeJobRef;
import com.streamarr.transcode.engine.model.TranscodeJobSpec;
import com.streamarr.transcode.engine.model.TranscodeJobState;
import com.streamarr.transcode.engine.model.TranscodeMode;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Transcode Worker Contract Value Tests")
class TranscodeWorkerContractValuesTest {

  @Test
  @DisplayName("Should require exact worker and boot identities for a worker target")
  void shouldRequireExactWorkerAndBootIdentitiesForWorkerTarget() {
    var workerId = UUID.randomUUID();
    var bootId = UUID.randomUUID();

    assertThat(new WorkerTarget(workerId, bootId)).isEqualTo(new WorkerTarget(workerId, bootId));
    assertThatThrownBy(() -> new WorkerTarget(null, bootId))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WorkerTarget(workerId, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should require delivery identity, exact target, and job intent for a start command")
  void shouldRequireDeliveryIdentityExactTargetAndJobIntentForStartCommand() {
    var commandId = UUID.randomUUID();
    var target = workerTarget();
    var specification = jobSpecification();

    var command =
        StartJobCommand.builder()
            .commandId(commandId)
            .target(target)
            .specification(specification)
            .build();

    assertThat(command.commandId()).isEqualTo(commandId);
    assertThat(command.target()).isEqualTo(target);
    assertThat(command.specification()).isEqualTo(specification);
    assertThatThrownBy(
            () ->
                StartJobCommand.builder()
                    .commandId(null)
                    .target(target)
                    .specification(specification)
                    .build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                StartJobCommand.builder()
                    .commandId(commandId)
                    .target(null)
                    .specification(specification)
                    .build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                StartJobCommand.builder()
                    .commandId(commandId)
                    .target(target)
                    .specification(null)
                    .build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should require delivery identity, exact target, and generation for a stop command")
  void shouldRequireDeliveryIdentityExactTargetAndGenerationForStopCommand() {
    var commandId = UUID.randomUUID();
    var target = workerTarget();
    var jobRef = new TranscodeJobRef(UUID.randomUUID(), 2);

    var command =
        StopJobCommand.builder().commandId(commandId).target(target).jobRef(jobRef).build();

    assertThat(command.commandId()).isEqualTo(commandId);
    assertThat(command.target()).isEqualTo(target);
    assertThat(command.jobRef()).isEqualTo(jobRef);
    assertThatThrownBy(
            () -> StopJobCommand.builder().commandId(null).target(target).jobRef(jobRef).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> StopJobCommand.builder().commandId(commandId).target(null).jobRef(jobRef).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> StopJobCommand.builder().commandId(commandId).target(target).jobRef(null).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should require exact target and generation for an inspection query")
  void shouldRequireExactTargetAndGenerationForInspectionQuery() {
    var target = workerTarget();
    var jobRef = new TranscodeJobRef(UUID.randomUUID(), 3);

    var query = new InspectJobQuery(target, jobRef);

    assertThat(query.target()).isEqualTo(target);
    assertThat(query.jobRef()).isEqualTo(jobRef);
    assertThatThrownBy(() -> new InspectJobQuery(null, jobRef))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InspectJobQuery(target, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should model start acceptance, rejection, and cleanup uncertainty explicitly")
  void shouldModelStartAcceptanceRejectionAndCleanupUncertaintyExplicitly() {
    var observation = runningObservation();
    var jobRef = observation.jobRef();

    assertThat(new StartJobResult.Accepted(observation).observation()).isEqualTo(observation);
    assertThat(new StartJobResult.Rejected(StartJobRejection.CAPACITY_EXHAUSTED).reason())
        .isEqualTo(StartJobRejection.CAPACITY_EXHAUSTED);
    assertThat(new StartJobResult.CleanupPending(jobRef).jobRef()).isEqualTo(jobRef);
    assertThatThrownBy(() -> new StartJobResult.Accepted(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StartJobResult.Rejected(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StartJobResult.CleanupPending(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "Should model stop completion, absence, rejection, and cleanup uncertainty explicitly")
  void shouldModelStopCompletionAbsenceRejectionAndCleanupUncertaintyExplicitly() {
    var jobRef = new TranscodeJobRef(UUID.randomUUID(), 4);

    assertThat(new StopJobResult.Stopped(jobRef).jobRef()).isEqualTo(jobRef);
    assertThat(new StopJobResult.AlreadyAbsent(jobRef).jobRef()).isEqualTo(jobRef);
    assertThat(new StopJobResult.CleanupPending(jobRef).jobRef()).isEqualTo(jobRef);
    assertThat(new StopJobResult.Rejected(StopJobRejection.STALE_GENERATION).reason())
        .isEqualTo(StopJobRejection.STALE_GENERATION);
    assertThatThrownBy(() -> new StopJobResult.Stopped(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StopJobResult.AlreadyAbsent(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StopJobResult.CleanupPending(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StopJobResult.Rejected(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static WorkerTarget workerTarget() {
    return new WorkerTarget(UUID.randomUUID(), UUID.randomUUID());
  }

  private static TranscodeJobSpec jobSpecification() {
    return TranscodeJobSpec.builder()
        .sessionId(UUID.randomUUID())
        .jobRef(new TranscodeJobRef(UUID.randomUUID(), 1))
        .source(new MediaSourceRef(UUID.randomUUID(), "Movies/movie.mkv"))
        .decision(
            TranscodeDecision.builder()
                .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                .videoCodecFamily("h264")
                .audioDecision(AudioDecision.stereoAac())
                .subtitleDecision(SubtitleDecision.exclude())
                .containerFormat(ContainerFormat.MPEGTS)
                .build())
        .execution(
            TranscodeExecutionParameters.builder()
                .seekPosition(0)
                .segmentDuration(6)
                .framerate(23.976)
                .startNumber(0)
                .startupTimeout(Duration.ofSeconds(45))
                .build())
        .renditions(List.of(new RenditionSpec("720p", 1280, 720, 3_000_000L)))
        .build();
  }

  private static TranscodeJobObservation runningObservation() {
    return TranscodeJobObservation.builder()
        .jobRef(new TranscodeJobRef(UUID.randomUUID(), 1))
        .state(TranscodeJobState.RUNNING)
        .renditions(List.of(new RenditionObservation("720p", RenditionState.RUNNING)))
        .build();
  }
}

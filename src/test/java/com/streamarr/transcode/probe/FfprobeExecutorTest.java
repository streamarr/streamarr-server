package com.streamarr.transcode.probe;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeContainerInfo;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Tests")
class FfprobeExecutorTest {

  private static final Path SOURCE = Path.of("/media/movie.mkv");

  @Test
  @DisplayName("Should return an execution failure when the process output cannot be read")
  void shouldReturnAnExecutionFailureWhenTheProcessOutputCannotBeRead() {
    var process =
        new CompletedProcess("", 0) {
          @Override
          public InputStream getInputStream() {
            return new InputStream() {
              @Override
              public int read() throws IOException {
                throw new IOException("output pipe unavailable");
              }
            };
          }
        };
    var executor = new FfprobeExecutor(new ObjectMapper(), _ -> process);

    assertThat(executor.probe(SOURCE, request()).getFailure())
        .isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
  }

  @Test
  @DisplayName("Should await process termination when cancellation is interrupted repeatedly")
  void shouldAwaitProcessTerminationWhenCancellationIsInterruptedRepeatedly() throws Exception {
    var process = new DelayedTerminationProcess();
    var executor = new FfprobeExecutor(new ObjectMapper(), _ -> process);
    var request = request();
    var completion = new CompletableFuture<ProbeAttemptResult>();
    var task = Thread.ofVirtual().start(() -> completion.complete(executor.probe(SOURCE, request)));
    try {
      assertThat(process.started.await(2, TimeUnit.SECONDS)).isTrue();
      task.interrupt();
      assertThat(process.terminationRequested.await(2, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> completion.get(100, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);
      task.interrupt();
      assertThatThrownBy(() -> completion.get(100, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);
      process.finish();

      assertThat(completion.get(2, TimeUnit.SECONDS).getFailure())
          .isEqualTo(ProbeFailure.PROBE_FAILURE_CANCELLED);
      assertThat(process.isAlive()).isFalse();
    } finally {
      process.finish();
      task.interrupt();
      task.join(Duration.ofSeconds(2));
    }
  }

  @Test
  @DisplayName("Should avoid launching ffprobe when the attempt is already interrupted")
  void shouldAvoidLaunchingFfprobeWhenTheAttemptIsAlreadyInterrupted() {
    var executor =
        new FfprobeExecutor(
            new ObjectMapper(),
            _ -> {
              throw new AssertionError("Cancelled attempts must not launch ffprobe");
            });
    Thread.currentThread().interrupt();
    try {
      assertThat(executor.probe(SOURCE, request()).getFailure())
          .isEqualTo(ProbeFailure.PROBE_FAILURE_CANCELLED);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("Should preserve absent fields and fallback indices when optional fields are null")
  void shouldPreserveAbsentFieldsAndFallbackIndicesWhenOptionalFieldsAreNull() {
    var executor =
        executor(
            """
        {"format":null,"streams":[
          {"index":null,"codec_type":"unknown","codec_name":null,"tags":null,"disposition":null},
          {"codec_type":"video","width":null,"height":null,"r_frame_rate":null,"channels":6},
          {"codec_type":"audio","channels":null,"bit_rate":null,"tags":{"language":null},
           "disposition":{"default":null,"forced":1}}]}
        """,
            0);

    var media = executor.probe(SOURCE, request()).getMedia();

    assertThat(media.hasContainer()).isTrue();
    assertThat(media.getContainer()).isEqualTo(ProbeContainerInfo.getDefaultInstance());
    assertThat(media.getStreamsList())
        .containsExactly(
            ProbeStreamInfo.newBuilder().setIndex(0).setCodecType("unknown").build(),
            ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("video").build(),
            ProbeStreamInfo.newBuilder()
                .setIndex(2)
                .setCodecType("audio")
                .setIsForced(true)
                .build());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -12345})
  @DisplayName("Should retain a retryable failure when ffprobe reports an unknown execution error")
  void shouldRetainARetryableFailureWhenFfprobeReportsAnUnknownExecutionError(int errorCode) {
    var result =
        executor(
                """
        {"error":{"code":%d}}
        """
                    .formatted(errorCode),
                1)
            .probe(SOURCE, request());

    assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
  }

  @Test
  @DisplayName("Should return an execution failure when the process cannot start")
  void shouldReturnAnExecutionFailureWhenTheProcessCannotStart() {
    var executor =
        new FfprobeExecutor(
            new ObjectMapper(),
            _ -> {
              throw new UncheckedIOException(new IOException("binary unavailable"));
            });

    assertThat(executor.probe(SOURCE, request()).getFailure())
        .isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
  }

  @ParameterizedTest
  @MethodSource("malformedProbeOutput")
  @DisplayName("Should return a retryable failure when ffprobe output is absent or malformed")
  void shouldReturnARetryableFailureWhenFfprobeOutputIsAbsentOrMalformed(String json) {
    var result = executor(json, 0).probe(SOURCE, request());

    assertThat(result.hasMedia()).isFalse();
    assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
  }

  private static Stream<String> malformedProbeOutput() {
    var missingResults =
        Stream.of(
            "",
            "null",
            "not json",
            "[]",
            "{\"streams\":\"invalid\"}",
            "{\"streams\":[{}, {\"codec_type\":\"video\"}]}",
            "{\"streams\":[{\"codec_type\":null}, {\"codec_type\":\"video\"}]}",
            "{\"streams\":[{\"codec_type\":\"video\"}, {}]}",
            "{\"streams\":[{\"codec_type\":\"video\"}, {\"codec_type\":null}]}");
    var malformedCodecTypes =
        Stream.of("7", "1.5", "true", "false", "\"\"", "\" \\t\\n\"", "{}", "[]")
            .flatMap(
                codecType ->
                    Stream.of(
                        """
                        {"streams":[{"codec_type":%s}]}
                        """
                            .formatted(codecType),
                        """
                        {"streams":[{"codec_type":%s},{"codec_type":"video"}]}
                        """
                            .formatted(codecType),
                        """
                        {"streams":[{"codec_type":"video"},{"codec_type":%s}]}
                        """
                            .formatted(codecType)));
    return Stream.concat(missingResults, malformedCodecTypes);
  }

  @ParameterizedTest
  @ValueSource(ints = {-2, -5, -13, -20})
  @DisplayName("Should report a retryable source failure when ffprobe cannot read the source")
  void shouldReportARetryableSourceFailureWhenFfprobeCannotReadTheSource(int errorCode) {
    var result =
        executor(
                """
        {"error":{"code":%d}}
        """
                    .formatted(errorCode),
                1)
            .probe(SOURCE, request());

    assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_SOURCE_UNAVAILABLE);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1094995529, -541478725})
  @DisplayName("Should return invalid media when ffprobe reports corrupt or truncated input")
  void shouldReturnInvalidMediaWhenFfprobeReportsCorruptOrTruncatedInput(int errorCode) {
    var executor =
        executor(
            """
        {"error":{"code":%d,"string":"local diagnostic must not be transmitted"}}
        """
                .formatted(errorCode),
            1);
    var request = request();

    var result = executor.probe(SOURCE, request);

    assertThat(result)
        .isEqualTo(
            ProbeAttemptResult.newBuilder()
                .setProbeAttemptId(request.getProbeAttemptId())
                .setProbeVersion(request.getProbeVersion())
                .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
                .build());
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"streams\":[]}", "{\"streams\":[{\"codec_type\":\"audio\"}]}"})
  @DisplayName("Should return a terminal failure when successful probing finds no video")
  void shouldReturnATerminalFailureWhenSuccessfulProbingFindsNoVideo(String json) {
    var result = executor(json, 0).probe(SOURCE, request());

    assertThat(result.hasMedia()).isFalse();
    assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_NO_VIDEO_STREAM);
  }

  @ParameterizedTest
  @CsvSource({"-1,-1", "-0.1,0", "-1,0/0", "-1,24/0", "-1,invalid"})
  @DisplayName("Should omit invalid timing values when duration or frame rate cannot be used")
  void shouldOmitInvalidTimingValuesWhenDurationOrFrameRateCannotBeUsed(
      String duration, String rate) {
    var executor =
        executor(
            """
        {"format":{"duration":"%s"},"streams":[{"codec_type":"video","r_frame_rate":"%s"}]}
        """
                .formatted(duration, rate),
            0);

    var media = executor.probe(SOURCE, request()).getMedia();

    assertThat(media.getContainer().hasDuration()).isFalse();
    assertThat(media.getStreamsList())
        .singleElement()
        .satisfies(stream -> assertThat(stream.hasFramerate()).isFalse());
  }

  @ParameterizedTest
  @ValueSource(strings = {"N/A", "NaN", "Infinity", "-Infinity", ""})
  @DisplayName("Should preserve absent numeric properties when ffprobe reports unavailable values")
  void shouldPreserveAbsentNumericPropertiesWhenFfprobeReportsUnavailableValues(String value) {
    var executor =
        executor(
            """
        {"format":{"duration":"%1$s","bit_rate":"%1$s"},
         "streams":[{"codec_type":"video","width":"%1$s","height":"%1$s",
                      "r_frame_rate":"%1$s","bit_rate":"%1$s"},
                    {"codec_type":"audio","channels":"%1$s"}]}
        """
                .formatted(value),
            0);

    var result = executor.probe(SOURCE, request());

    assertThat(result.hasMedia()).isTrue();
    assertThat(result.getMedia().getContainer().hasDuration()).isFalse();
    assertThat(result.getMedia().getContainer().hasBitrateBitsPerSecond()).isFalse();
    var video = result.getMedia().getStreams(0);
    assertThat(video.hasWidth()).isFalse();
    assertThat(video.hasHeight()).isFalse();
    assertThat(video.hasBitrateBitsPerSecond()).isFalse();
    assertThat(video.hasFramerate()).isFalse();
    assertThat(result.getMedia().getStreams(1).hasChannels()).isFalse();
  }

  @Test
  @DisplayName("Should retain complete container and ordered streams when probing multiple tracks")
  void shouldRetainCompleteContainerAndOrderedStreamsWhenProbingMultipleTracks() {
    var executor =
        executor(
            """
        {"format":{"format_name":"matroska,webm","duration":"7200.123","bit_rate":"16000000"},
         "streams":[
          {"index":4,"codec_type":"video","codec_name":"h264","width":1920,"height":1080,
           "r_frame_rate":"24000/1001","bit_rate":"4000000","disposition":{"default":0}},
          {"index":1,"codec_type":"audio","codec_name":"ac3","channels":6,"bit_rate":"384000",
           "tags":{"language":"eng"},"disposition":{"default":1,"forced":1}},
          {"index":9,"codec_type":"video","codec_name":"hevc","width":3840,"height":2160,
           "r_frame_rate":"60","bit_rate":"12000000","disposition":{"default":1}},
          {"index":3,"codec_type":"subtitle","codec_name":"srt","tags":{"language":"fra"}},
          {"index":6,"codec_type":"data","codec_name":"bin_data","bit_rate":"1000"},
          {"index":7,"codec_type":"attachment","codec_name":"ttf"},
          {"index":8,"codec_type":"unknown","codec_name":"unknown"}]}
        """,
            0);

    var result = executor.probe(SOURCE, request());

    assertThat(result.hasMedia()).isTrue();
    var media = result.getMedia();
    var container = media.getContainer();
    assertThat(container.getFormat()).isEqualTo("matroska,webm");
    assertThat(container.getDuration().getSeconds()).isEqualTo(7200);
    assertThat(container.getDuration().getNanos()).isEqualTo(123_000_000);
    assertThat(container.getBitrateBitsPerSecond()).isEqualTo(16_000_000);
    assertThat(media.getStreamsList())
        .extracting(ProbeStreamInfo::getIndex)
        .containsExactly(4, 1, 9, 3, 6, 7, 8);
    assertThat(media.getStreams(0))
        .isEqualTo(
            ProbeStreamInfo.newBuilder()
                .setIndex(4)
                .setCodecType("video")
                .setCodec("h264")
                .setWidth(1920)
                .setHeight(1080)
                .setFramerate(24000.0 / 1001)
                .setBitrateBitsPerSecond(4_000_000)
                .build());
    assertThat(media.getStreams(1))
        .isEqualTo(
            ProbeStreamInfo.newBuilder()
                .setIndex(1)
                .setCodecType("audio")
                .setCodec("ac3")
                .setChannels(6)
                .setBitrateBitsPerSecond(384_000)
                .setLanguage("eng")
                .setIsDefault(true)
                .setIsForced(true)
                .build());
    assertThat(media.getStreams(2))
        .isEqualTo(
            ProbeStreamInfo.newBuilder()
                .setIndex(9)
                .setCodecType("video")
                .setCodec("hevc")
                .setWidth(3840)
                .setHeight(2160)
                .setFramerate(60)
                .setBitrateBitsPerSecond(12_000_000)
                .setIsDefault(true)
                .build());
    assertThat(media.getStreams(3).getLanguage()).isEqualTo("fra");
    assertThat(media.getStreams(4).getBitrateBitsPerSecond()).isEqualTo(1000);
    assertThat(media.getStreams(5).getCodecType()).isEqualTo("attachment");
    assertThat(media.getStreams(6).getCodecType()).isEqualTo("unknown");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2, -1})
  @DisplayName("Should refuse unsupported versions when receiving a probe request")
  void shouldRefuseUnsupportedVersionsWhenReceivingAProbeRequest(int version) {
    var executor =
        new FfprobeExecutor(
            new ObjectMapper(),
            _ -> {
              throw new AssertionError("Unsupported requests must not launch ffprobe");
            });
    var request = request().toBuilder().setProbeVersion(version).build();

    var result = executor.probe(SOURCE, request);

    assertThat(FfprobeExecutor.PROBE_VERSION).isEqualTo(1);
    assertThat(result.getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
    assertThat(result.getProbeVersion()).isEqualTo(version);
    assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_UNSUPPORTED_VERSION);
  }

  @Test
  @DisplayName("Should return a typed result when the source has a video stream")
  void shouldReturnATypedResultWhenTheSourceHasAVideoStream() {
    var executor =
        executor(
            """
        {"streams":[{"index":2,"codec_type":"video","codec_name":"h264"}]}
        """,
            0);
    var request = request();

    var result = executor.probe(SOURCE, request);

    assertThat(result.getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
    assertThat(result.getProbeVersion()).isEqualTo(request.getProbeVersion());
    assertThat(result.hasMedia()).isTrue();
    assertThat(result.getMedia().getStreamsList())
        .singleElement()
        .satisfies(
            stream -> {
              assertThat(stream.getIndex()).isEqualTo(2);
              assertThat(stream.getCodecType()).isEqualTo("video");
              assertThat(stream.getCodec()).isEqualTo("h264");
            });
  }

  private ProbeRequest request() {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(1)
        .build();
  }

  private FfprobeExecutor executor(String stdout, int exitCode) {
    return new FfprobeExecutor(new ObjectMapper(), _ -> new CompletedProcess(stdout, exitCode));
  }

  private static class CompletedProcess extends Process {
    private final InputStream stdout;
    private final int exitCode;

    CompletedProcess(String stdout, int exitCode) {
      this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
      this.exitCode = exitCode;
    }

    @Override
    public OutputStream getOutputStream() {
      return new ByteArrayOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return stdout;
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() throws InterruptedException {
      return exitCode;
    }

    @Override
    public int exitValue() {
      return exitCode;
    }

    @Override
    public void destroy() {
      // This fake has already exited.
    }
  }

  private static final class DelayedTerminationProcess extends CompletedProcess {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch terminationRequested = new CountDownLatch(1);
    private final CountDownLatch terminated = new CountDownLatch(1);

    private DelayedTerminationProcess() {
      super("{\"streams\":[{\"codec_type\":\"video\"}]}", 137);
    }

    @Override
    public int waitFor() throws InterruptedException {
      started.countDown();
      terminated.await();
      return 137;
    }

    @Override
    public boolean isAlive() {
      return terminated.getCount() != 0;
    }

    @Override
    public void destroy() {
      terminationRequested.countDown();
    }

    private void finish() {
      terminated.countDown();
    }
  }
}

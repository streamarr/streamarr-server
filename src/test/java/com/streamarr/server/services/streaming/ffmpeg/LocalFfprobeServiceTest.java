package com.streamarr.server.services.streaming.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.exceptions.TranscodeException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Local Ffprobe Service Tests")
class LocalFfprobeServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @ParameterizedTest
  @MethodSource("streamsWithoutCodecTypes")
  @DisplayName("Should reject malformed output when a stream has no codec type")
  void shouldRejectMalformedOutputWhenAStreamHasNoCodecType(String json) {
    var filepath = Path.of("/test/movie.mkv");
    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    assertThatThrownBy(() -> service.probe(filepath))
        .isInstanceOf(ProbeExecutionException.class)
        .hasMessage(TranscodeException.GENERIC_MESSAGE)
        .hasRootCauseMessage("ffprobe stream is missing codec_type");
  }

  private static Stream<String> streamsWithoutCodecTypes() {
    return Stream.of("{}", "{\"codec_type\":null}")
        .flatMap(
            malformed ->
                Stream.of(
                    """
                    {"streams": [%s, {"codec_type": "video"}]}
                    """
                        .formatted(malformed),
                    """
                    {"streams": [{"codec_type": "video"}, %s]}
                    """
                        .formatted(malformed)));
  }

  @Test
  @DisplayName("Should retain unknown streams when ffprobe explicitly reports their type")
  void shouldRetainUnknownStreamsWhenFfprobeExplicitlyReportsTheirType() {
    var json =
        """
        {"streams": [
          {"index": 0, "codec_type": "unknown"},
          {"index": 1, "codec_type": "video", "codec_name": "h264"}
        ]}
        """;
    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    assertThat(service.probe(Path.of("/test/movie.mkv")))
        .isInstanceOfSatisfying(
            ProbeOutcome.Success.class,
            success -> {
              assertThat(success.streams()).hasSize(2);
              assertThat(success.streams().getFirst().codecType()).isEqualTo("unknown");
              assertThat(success.mediaProbe().videoCodec()).isEqualTo("h264");
            });
  }

  @Test
  @DisplayName("Should return a terminal media failure when a truncated input ends during probing")
  void shouldReturnATerminalMediaFailureWhenATruncatedInputEndsDuringProbing() {
    var json =
        """
        {"error": {"code": -541478725, "string": "End of file"}}
        """;
    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 1));

    assertThat(service.probe(Path.of("/test/truncated.m2ts")))
        .isEqualTo(new ProbeOutcome.Failure(ProbeError.INVALID_MEDIA));
  }

  @Test
  @DisplayName("Should summarize first tracks when later tracks have default flags")
  void shouldSummarizeFirstTracksWhenLaterTracksHaveDefaultFlags() {
    var json =
        """
        {
          "streams": [
            {"index": 0, "codec_type": "video", "codec_name": "h264", "width": 1920,
             "height": 1080, "r_frame_rate": "24/1", "disposition": {"default": 0}},
            {"index": 1, "codec_type": "audio", "codec_name": "ac3", "channels": 6,
             "bit_rate": "384000", "disposition": {"default": 0}},
            {"index": 2, "codec_type": "video", "codec_name": "hevc", "width": 3840,
             "height": 2160, "r_frame_rate": "60/1", "disposition": {"default": 1}},
            {"index": 3, "codec_type": "audio", "codec_name": "aac", "channels": 2,
             "bit_rate": "128000", "disposition": {"default": 1}},
            {"index": 4, "codec_type": "data", "bit_rate": "1000"},
            {"index": 5, "codec_type": "attachment", "codec_name": "ttf"}
          ],
          "format": {"duration": "90", "bit_rate": "15000000"}
        }
        """;
    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    var probe = service.probeMedia(Path.of("/test/movie.mkv"));

    assertThat(probe.videoCodec()).isEqualTo("h264");
    assertThat(probe.width()).isEqualTo(1920);
    assertThat(probe.height()).isEqualTo(1080);
    assertThat(probe.framerate()).isEqualTo(24);
    assertThat(probe.audioCodec()).isEqualTo("ac3");
    assertThat(probe.audioChannels()).hasValue(6);
    assertThat(probe.audioBitrate()).hasValue(384_000L);
    assertThat(probe.streams()).hasSize(6);
    assertThat(probe.streams().get(4).codecType()).isEqualTo("data");
    assertThat(probe.streams().get(4).bitrate()).hasValue(1000L);
    assertThat(probe.streams().getLast().codecType()).isEqualTo("attachment");
  }

  @Test
  @DisplayName("Should retain a retryable failure when ffprobe cannot start")
  void shouldRetainARetryableFailureWhenFfprobeCannotStart() {
    var filepath = Path.of("/test/movie.mkv");
    var cause = new UncheckedIOException(new IOException("executable unavailable"));
    var service =
        new LocalFfprobeService(
            objectMapper,
            path -> {
              throw cause;
            });

    assertThatThrownBy(() -> service.probe(filepath))
        .isInstanceOf(ProbeExecutionException.class)
        .hasCause(cause)
        .hasMessage(TranscodeException.GENERIC_MESSAGE);
  }

  @ParameterizedTest
  @ValueSource(ints = {-5, -2, -13, -12345})
  @DisplayName("Should retain a retryable failure when ffprobe reports storage or execution errors")
  void shouldRetainARetryableFailureWhenFfprobeReportsStorageOrExecutionErrors(int code) {
    var filepath = Path.of("/test/movie.mkv");
    var json =
        """
        {"error": {"code": %d, "string": "input unavailable"}}
        """
            .formatted(code);
    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 1));

    assertThatThrownBy(() -> service.probe(filepath))
        .isInstanceOf(ProbeExecutionException.class)
        .hasMessage(TranscodeException.GENERIC_MESSAGE);
  }

  @Test
  @DisplayName("Should return a terminal outcome when the media has no video stream")
  void shouldReturnATerminalOutcomeWhenTheMediaHasNoVideoStream() {
    var filepath = Path.of("/test/audio.m4a");
    var service =
        new LocalFfprobeService(
            objectMapper,
            path ->
                createFakeProcess(
                    """
        {"streams": [{"codec_type": "audio", "codec_name": "aac"}]}
        """,
                    0));

    assertThat(service.probe(filepath))
        .isEqualTo(new ProbeOutcome.Failure(ProbeError.NO_VIDEO_STREAM));
    assertThatThrownBy(() -> service.probeMedia(filepath))
        .isInstanceOf(TranscodeException.class)
        .hasMessage(TranscodeException.GENERIC_MESSAGE);
  }

  @Test
  @DisplayName("Should preserve unknown numeric properties when ffprobe reports unavailable values")
  void shouldPreserveUnknownNumericPropertiesWhenFfprobeReportsUnavailableValues() {
    var service =
        new LocalFfprobeService(
            objectMapper,
            path ->
                createFakeProcess(
                    """
        {"streams": [{"codec_type": "video", "width": "N/A", "height": null,
                      "r_frame_rate": "0/0", "bit_rate": "N/A"}],
         "format": {"duration": "N/A", "bit_rate": "N/A"}}
        """,
                    0));

    assertThat(service.probe(Path.of("/test/movie.mkv")))
        .isInstanceOfSatisfying(
            ProbeOutcome.Success.class,
            success -> {
              assertThat(success.container().duration()).isEmpty();
              assertThat(success.container().bitrate()).isEmpty();
              var stream = success.streams().getFirst();
              assertThat(stream.width()).isEmpty();
              assertThat(stream.height()).isEmpty();
              assertThat(stream.framerate()).isEmpty();
              assertThat(stream.bitrate()).isEmpty();
            });
  }

  @Test
  @DisplayName("Should preserve unknown properties when ffprobe omits optional fields")
  void shouldPreserveUnknownPropertiesWhenFfprobeOmitsOptionalFields() {
    var service =
        new LocalFfprobeService(
            objectMapper,
            path ->
                createFakeProcess(
                    """
        {"streams": [{"index": 0, "codec_type": "video"}]}
        """,
                    0));

    assertThat(service.probe(Path.of("/test/movie.mkv")))
        .isInstanceOfSatisfying(
            ProbeOutcome.Success.class,
            success -> {
              assertThat(success.container().format()).isEmpty();
              assertThat(success.container().duration()).isEmpty();
              assertThat(success.container().bitrate()).isEmpty();
              var stream = success.streams().getFirst();
              assertThat(stream.codec()).isEmpty();
              assertThat(stream.width()).isEmpty();
              assertThat(stream.height()).isEmpty();
              assertThat(stream.framerate()).isEmpty();
              assertThat(stream.bitrate()).isEmpty();
            });
  }

  @Test
  @DisplayName("Should retain each video stream when probing multiple video tracks")
  void shouldRetainEachVideoStreamWhenProbingMultipleVideoTracks() {
    var json =
        """
        {
          "streams": [
            {"index": 0, "codec_type": "video", "codec_name": "h264", "width": 1920,
             "height": 1080, "r_frame_rate": "24000/1001", "bit_rate": "4000000"},
            {"index": 2, "codec_type": "video", "codec_name": "hevc", "width": 3840,
             "height": 2160, "r_frame_rate": "60/1", "bit_rate": "12000000"}
          ],
          "format": {"format_name": "matroska,webm", "duration": "7200.123",
                     "bit_rate": "16000000"}
        }
        """;
    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    var outcome = service.probe(Path.of("/test/movie.mkv"));

    assertThat(outcome)
        .isInstanceOfSatisfying(
            ProbeOutcome.Success.class,
            success -> {
              assertThat(success.container().format()).hasValue("matroska,webm");
              assertThat(success.container().duration()).hasValue(Duration.ofMillis(7_200_123));
              assertThat(success.container().bitrate()).hasValue(16_000_000L);
              assertThat(success.streams()).hasSize(2);
              assertThat(success.streams().getFirst().width()).hasValue(1920);
              assertThat(success.streams().getFirst().height()).hasValue(1080);
              assertThat(success.streams().getFirst().framerate()).hasValue(24000.0 / 1001);
              assertThat(success.streams().getFirst().bitrate()).hasValue(4_000_000L);
              assertThat(success.streams().getLast().width()).hasValue(3840);
              assertThat(success.streams().getLast().height()).hasValue(2160);
              assertThat(success.streams().getLast().framerate()).hasValue(60);
              assertThat(success.streams().getLast().bitrate()).hasValue(12_000_000L);
            });
  }

  @Test
  @DisplayName("Should parse into media probe when ffprobe output is valid")
  void shouldParseIntoMediaProbeWhenFfprobeOutputIsValid() {
    var json =
        """
        {
          "streams": [
            {
              "codec_type": "video",
              "codec_name": "h264",
              "width": 1920,
              "height": 1080,
              "r_frame_rate": "24000/1001"
            },
            {
              "codec_type": "audio",
              "codec_name": "aac"
            }
          ],
          "format": {
            "duration": "7200.123",
            "bit_rate": "5000000"
          }
        }
        """;

    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    var probe = service.probeMedia(Path.of("/test/movie.mkv"));

    assertThat(probe.videoCodec()).isEqualTo("h264");
    assertThat(probe.audioCodec()).isEqualTo("aac");
    assertThat(probe.width()).isEqualTo(1920);
    assertThat(probe.height()).isEqualTo(1080);
    assertThat(probe.framerate()).isCloseTo(23.976, within(0.001));
    assertThat(probe.bitrate()).isEqualTo(5_000_000L);
    assertThat(probe.duration().getSeconds()).isEqualTo(7200L);
  }

  @Test
  @DisplayName("Should parse fraction framerate when rate is expressed as fraction")
  void shouldParseFractionFramerateWhenRateIsExpressedAsFraction() {
    var json =
        """
        {
          "streams": [
            {
              "codec_type": "video",
              "codec_name": "h264",
              "width": 1280,
              "height": 720,
              "r_frame_rate": "30/1"
            },
            {
              "codec_type": "audio",
              "codec_name": "aac"
            }
          ],
          "format": {
            "duration": "3600.0",
            "bit_rate": "3000000"
          }
        }
        """;

    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    var probe = service.probeMedia(Path.of("/test/movie.mkv"));

    assertThat(probe.framerate()).isCloseTo(30.0, within(0.001));
  }

  @Test
  @DisplayName("Should throw when no video stream found")
  void shouldThrowWhenNoVideoStreamFound() {
    var json =
        """
        {
          "streams": [
            {
              "codec_type": "audio",
              "codec_name": "aac"
            }
          ],
          "format": {
            "duration": "3600.0",
            "bit_rate": "128000"
          }
        }
        """;

    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    var filePath = Path.of("/test/audio-only.mkv");

    assertThatThrownBy(() -> service.probeMedia(filePath))
        .isInstanceOf(TranscodeException.class)
        .hasMessage(TranscodeException.GENERIC_MESSAGE);
  }

  @Test
  @DisplayName("Should throw when ffprobe process fails")
  void shouldThrowWhenFfprobeProcessFails() {
    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess("", 1));

    var filePath = Path.of("/test/movie.mkv");

    assertThatThrownBy(() -> service.probe(filePath))
        .isInstanceOf(ProbeExecutionException.class)
        .hasMessage(TranscodeException.GENERIC_MESSAGE);
  }

  @Test
  @DisplayName("Should set null audio codec when no audio stream is found")
  void shouldSetNullAudioCodecWhenNoAudioStreamIsFound() {
    var json =
        """
        {
          "streams": [
            {
              "codec_type": "video",
              "codec_name": "h264",
              "width": 1920,
              "height": 1080,
              "r_frame_rate": "24/1"
            }
          ],
          "format": {
            "duration": "60.0",
            "bit_rate": "5000000"
          }
        }
        """;

    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    var probe = service.probeMedia(Path.of("/test/silent.mkv"));

    assertThat(probe.videoCodec()).isEqualTo("h264");
    assertThat(probe.audioCodec()).isNull();
  }

  @Test
  @DisplayName("Should parse audio channels when present")
  void shouldParseAudioChannelsWhenPresent() {
    var json =
        """
        {
          "streams": [
            {
              "codec_type": "video",
              "codec_name": "h264",
              "width": 1920,
              "height": 1080,
              "r_frame_rate": "24/1"
            },
            {
              "codec_type": "audio",
              "codec_name": "ac3",
              "channels": 6,
              "bit_rate": "384000",
              "sample_rate": "48000"
            }
          ],
          "format": {
            "duration": "7200.0",
            "bit_rate": "5000000"
          }
        }
        """;

    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    var probe = service.probeMedia(Path.of("/test/movie.mkv"));

    assertThat(probe.audioChannels()).hasValue(6);
    assertThat(probe.audioBitrate()).hasValue(384_000L);
  }

  @Test
  @DisplayName("Should return empty bitrate when bit_rate is non-numeric")
  void shouldReturnEmptyBitrateWhenBitRateIsNonNumeric() {
    var json =
        """
        {
          "streams": [
            {
              "codec_type": "video",
              "codec_name": "h264",
              "width": 1920,
              "height": 1080,
              "r_frame_rate": "24/1"
            },
            {
              "codec_type": "audio",
              "codec_name": "flac",
              "channels": 2,
              "bit_rate": "N/A"
            }
          ],
          "format": {
            "duration": "60.0",
            "bit_rate": "5000000"
          }
        }
        """;

    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    var probe = service.probeMedia(Path.of("/test/movie.mkv"));

    assertThat(probe.audioBitrate()).isEmpty();
    assertThat(probe.audioChannels()).hasValue(2);
  }

  @Test
  @DisplayName("Should parse simple framerate when not expressed as fraction")
  void shouldParseSimpleFramerateWhenNotExpressedAsFraction() {
    var json =
        """
        {
          "streams": [
            {
              "codec_type": "video",
              "codec_name": "h264",
              "width": 1920,
              "height": 1080,
              "r_frame_rate": "25"
            },
            {
              "codec_type": "audio",
              "codec_name": "aac"
            }
          ],
          "format": {
            "duration": "60.0",
            "bit_rate": "5000000"
          }
        }
        """;

    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    var probe = service.probeMedia(Path.of("/test/movie.mkv"));

    assertThat(probe.framerate()).isCloseTo(25.0, within(0.001));
  }

  @Test
  @DisplayName("Should return empty audio channels when missing")
  void shouldReturnEmptyAudioChannelsWhenMissing() {
    var json =
        """
        {
          "streams": [
            {
              "codec_type": "video",
              "codec_name": "h264",
              "width": 1920,
              "height": 1080,
              "r_frame_rate": "24/1"
            },
            {
              "codec_type": "audio",
              "codec_name": "aac"
            }
          ],
          "format": {
            "duration": "60.0",
            "bit_rate": "5000000"
          }
        }
        """;

    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    var probe = service.probeMedia(Path.of("/test/movie.mkv"));

    assertThat(probe.audioChannels()).isEmpty();
    assertThat(probe.audioBitrate()).isEmpty();
  }

  @Test
  @DisplayName("Should parse container format from format node")
  void shouldParseContainerFormatFromFormatNode() {
    var json =
        """
        {
          "streams": [
            {
              "codec_type": "video",
              "codec_name": "h264",
              "width": 1920,
              "height": 1080,
              "r_frame_rate": "24/1"
            }
          ],
          "format": {
            "format_name": "matroska,webm",
            "duration": "60.0",
            "bit_rate": "5000000"
          }
        }
        """;

    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    var probe = service.probeMedia(Path.of("/test/movie.mkv"));

    assertThat(probe.containerFormat()).hasValue("matroska,webm");
  }

  @Test
  @DisplayName("Should return empty container format when format_name is absent")
  void shouldReturnEmptyContainerFormatWhenFormatNameIsAbsent() {
    var json =
        """
        {
          "streams": [
            {
              "codec_type": "video",
              "codec_name": "h264",
              "width": 1920,
              "height": 1080,
              "r_frame_rate": "24/1"
            }
          ],
          "format": {
            "duration": "60.0",
            "bit_rate": "5000000"
          }
        }
        """;

    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    var probe = service.probeMedia(Path.of("/test/movie.mkv"));

    assertThat(probe.containerFormat()).isEmpty();
  }

  @Test
  @DisplayName("Should build stream list with all stream types")
  void shouldBuildStreamListWithAllStreamTypes() {
    var json =
        """
        {
          "streams": [
            {
              "index": 0,
              "codec_type": "video",
              "codec_name": "h264",
              "width": 1920,
              "height": 1080,
              "r_frame_rate": "24/1",
              "tags": { "language": "und" },
              "disposition": { "default": 1, "forced": 0 }
            },
            {
              "index": 1,
              "codec_type": "audio",
              "codec_name": "ac3",
              "channels": 6,
              "bit_rate": "384000",
              "tags": { "language": "eng" },
              "disposition": { "default": 1, "forced": 0 }
            },
            {
              "index": 2,
              "codec_type": "subtitle",
              "codec_name": "subrip",
              "tags": { "language": "eng" },
              "disposition": { "default": 0, "forced": 0 }
            },
            {
              "index": 3,
              "codec_type": "subtitle",
              "codec_name": "hdmv_pgs_subtitle",
              "tags": { "language": "spa" },
              "disposition": { "default": 0, "forced": 1 }
            }
          ],
          "format": {
            "format_name": "matroska,webm",
            "duration": "7200.0",
            "bit_rate": "5000000"
          }
        }
        """;

    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    var probe = service.probeMedia(Path.of("/test/movie.mkv"));

    assertThat(probe.streams()).hasSize(4);

    var video = probe.streams().get(0);
    assertThat(video.index()).isZero();
    assertThat(video.codecType()).isEqualTo("video");
    assertThat(video.codec()).hasValue("h264");
    assertThat(video.language()).hasValue("und");
    assertThat(video.isDefault()).isTrue();

    var audio = probe.streams().get(1);
    assertThat(audio.index()).isEqualTo(1);
    assertThat(audio.codecType()).isEqualTo("audio");
    assertThat(audio.codec()).hasValue("ac3");
    assertThat(audio.language()).hasValue("eng");
    assertThat(audio.channels()).hasValue(6);
    assertThat(audio.bitrate()).hasValue(384_000L);

    var srtSub = probe.streams().get(2);
    assertThat(srtSub.codecType()).isEqualTo("subtitle");
    assertThat(srtSub.codec()).hasValue("subrip");
    assertThat(srtSub.language()).hasValue("eng");
    assertThat(srtSub.isForced()).isFalse();

    var pgsSub = probe.streams().get(3);
    assertThat(pgsSub.codec()).hasValue("hdmv_pgs_subtitle");
    assertThat(pgsSub.language()).hasValue("spa");
    assertThat(pgsSub.isForced()).isTrue();
  }

  @Test
  @DisplayName("Should parse stream index from ffprobe JSON when indices are non-sequential")
  void shouldParseStreamIndexFromFfprobeJsonWhenIndicesAreNonSequential() {
    var json =
        """
        {
          "streams": [
            {
              "index": 0,
              "codec_type": "video",
              "codec_name": "h264",
              "width": 1920,
              "height": 1080,
              "r_frame_rate": "24/1"
            },
            {
              "index": 2,
              "codec_type": "audio",
              "codec_name": "ac3",
              "channels": 6
            },
            {
              "index": 5,
              "codec_type": "subtitle",
              "codec_name": "subrip"
            }
          ],
          "format": {
            "duration": "60.0",
            "bit_rate": "5000000"
          }
        }
        """;

    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    var probe = service.probeMedia(Path.of("/test/movie.mkv"));

    assertThat(probe.streams()).hasSize(3);
    assertThat(probe.streams().get(0).index()).isZero();
    assertThat(probe.streams().get(1).index()).isEqualTo(2);
    assertThat(probe.streams().get(2).index()).isEqualTo(5);
  }

  @Test
  @DisplayName("Should return no subtitle streams when only video stream present")
  void shouldReturnNoSubtitleStreamsWhenOnlyVideoStreamPresent() {
    var json =
        """
        {
          "streams": [
            {
              "codec_type": "video",
              "codec_name": "h264",
              "width": 1920,
              "height": 1080,
              "r_frame_rate": "24/1"
            }
          ],
          "format": {
            "duration": "60.0",
            "bit_rate": "5000000"
          }
        }
        """;

    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    var probe = service.probeMedia(Path.of("/test/movie.mkv"));

    assertThat(probe.streams()).isNotEmpty();
    assertThat(probe.subtitleStreams()).isEmpty();
  }

  @Test
  @DisplayName("Should populate audio streams convenience method")
  void shouldPopulateAudioStreamsConvenienceMethod() {
    var json =
        """
        {
          "streams": [
            {
              "codec_type": "video",
              "codec_name": "h264",
              "width": 1920,
              "height": 1080,
              "r_frame_rate": "24/1"
            },
            {
              "codec_type": "audio",
              "codec_name": "ac3",
              "channels": 6,
              "tags": { "language": "eng" }
            },
            {
              "codec_type": "audio",
              "codec_name": "aac",
              "channels": 2,
              "tags": { "language": "jpn" }
            }
          ],
          "format": {
            "duration": "60.0",
            "bit_rate": "5000000"
          }
        }
        """;

    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    var probe = service.probeMedia(Path.of("/test/movie.mkv"));

    assertThat(probe.audioStreams()).hasSize(2);
    assertThat(probe.audioStreams().get(0).language()).hasValue("eng");
    assertThat(probe.audioStreams().get(1).language()).hasValue("jpn");
  }

  @Test
  @DisplayName("Should handle missing tags and disposition gracefully")
  void shouldHandleMissingTagsAndDispositionGracefully() {
    var json =
        """
        {
          "streams": [
            {
              "codec_type": "video",
              "codec_name": "h264",
              "width": 1920,
              "height": 1080,
              "r_frame_rate": "24/1"
            }
          ],
          "format": {
            "duration": "60.0",
            "bit_rate": "5000000"
          }
        }
        """;

    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    var probe = service.probeMedia(Path.of("/test/movie.mkv"));

    var video = probe.streams().getFirst();
    assertThat(video.language()).isEmpty();
    assertThat(video.isDefault()).isFalse();
    assertThat(video.isForced()).isFalse();
  }

  @Test
  @DisplayName("Should throw with generic message when ffprobe output cannot be parsed")
  void shouldThrowWithGenericMessageWhenFfprobeOutputCannotBeParsed() {
    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess("not json", 0));

    var filePath = Path.of("/test/corrupt.mkv");

    assertThatThrownBy(() -> service.probe(filePath))
        .isInstanceOf(TranscodeException.class)
        .hasMessage(TranscodeException.GENERIC_MESSAGE);
  }

  @Test
  @DisplayName("Should throw with generic message when ffprobe is interrupted")
  void shouldThrowWithGenericMessageWhenFfprobeIsInterrupted() {
    var service =
        new LocalFfprobeService(
            objectMapper,
            path ->
                new FakeProcess("{}", 0) {
                  @Override
                  public int waitFor() throws InterruptedException {
                    throw new InterruptedException("thread interrupted");
                  }
                });

    var filePath = Path.of("/test/movie.mkv");

    assertThatThrownBy(() -> service.probe(filePath))
        .isInstanceOf(TranscodeException.class)
        .hasMessage(TranscodeException.GENERIC_MESSAGE);
  }

  @Test
  @DisplayName("Should ignore explicit null metadata when stream fields are null")
  void shouldIgnoreExplicitNullMetadataWhenStreamFieldsAreNull() {
    var json =
        """
        {
          "streams": [
            {
              "index": null,
              "codec_type": "video",
              "codec_name": "h264",
              "width": 1920,
              "height": 1080,
              "r_frame_rate": "24/1",
              "tags": null,
              "disposition": null
            },
            {
              "codec_type": "audio",
              "codec_name": "aac",
              "channels": null,
              "bit_rate": null,
              "tags": { "language": null },
              "disposition": { "default": null, "forced": 1 }
            }
          ],
          "format": {
            "duration": "60.0",
            "bit_rate": "5000000"
          }
        }
        """;

    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    var probe = service.probeMedia(Path.of("/test/movie.mkv"));

    var video = probe.streams().getFirst();
    assertThat(video.index()).isZero();
    assertThat(video.language()).isEmpty();
    assertThat(video.isDefault()).isFalse();

    var audio = probe.streams().get(1);
    assertThat(audio.index()).isEqualTo(1);
    assertThat(audio.language()).isEmpty();
    assertThat(audio.channels()).isEmpty();
    assertThat(audio.bitrate()).isEmpty();
    assertThat(audio.isDefault()).isFalse();
    assertThat(audio.isForced()).isTrue();
  }

  @Test
  @DisplayName("Should throw with generic message when streams key is missing")
  void shouldThrowWithGenericMessageWhenStreamsKeyIsMissing() {
    var json =
        """
        {
          "format": {
            "duration": "60.0",
            "bit_rate": "5000000"
          }
        }
        """;

    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess(json, 0));

    var filePath = Path.of("/test/movie.mkv");

    assertThatThrownBy(() -> service.probeMedia(filePath))
        .isInstanceOf(TranscodeException.class)
        .hasMessage(TranscodeException.GENERIC_MESSAGE);
  }

  private Process createFakeProcess(String stdout, int exitCode) {
    return new FakeProcess(stdout, exitCode);
  }
}

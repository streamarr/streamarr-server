package com.streamarr.server.services.streaming.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.streamarr.server.exceptions.FfmpegNotAvailableException;
import com.streamarr.server.exceptions.TranscodeException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Local Ffprobe Service Tests")
class LocalFfprobeServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

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

    var probe = service.probe(Path.of("/test/movie.mkv"));

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

    var probe = service.probe(Path.of("/test/movie.mkv"));

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

    assertThatThrownBy(() -> service.probe(filePath))
        .isInstanceOf(TranscodeException.class)
        .hasMessage(TranscodeException.GENERIC_MESSAGE);
  }

  @Test
  @DisplayName("Should throw when ffprobe process fails")
  void shouldThrowWhenFfprobeProcessFails() {
    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess("", 1));

    var filePath = Path.of("/test/movie.mkv");

    assertThatThrownBy(() -> service.probe(filePath))
        .isInstanceOf(FfmpegNotAvailableException.class)
        .hasMessage(FfmpegNotAvailableException.GENERIC_MESSAGE);
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

    var probe = service.probe(Path.of("/test/silent.mkv"));

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

    var probe = service.probe(Path.of("/test/movie.mkv"));

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

    var probe = service.probe(Path.of("/test/movie.mkv"));

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

    var probe = service.probe(Path.of("/test/movie.mkv"));

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

    var probe = service.probe(Path.of("/test/movie.mkv"));

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

    var probe = service.probe(Path.of("/test/movie.mkv"));

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

    var probe = service.probe(Path.of("/test/movie.mkv"));

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

    var probe = service.probe(Path.of("/test/movie.mkv"));

    assertThat(probe.streams()).hasSize(4);

    var video = probe.streams().get(0);
    assertThat(video.index()).isZero();
    assertThat(video.codecType()).isEqualTo("video");
    assertThat(video.codec()).isEqualTo("h264");
    assertThat(video.language()).hasValue("und");
    assertThat(video.isDefault()).isTrue();

    var audio = probe.streams().get(1);
    assertThat(audio.index()).isEqualTo(1);
    assertThat(audio.codecType()).isEqualTo("audio");
    assertThat(audio.codec()).isEqualTo("ac3");
    assertThat(audio.language()).hasValue("eng");
    assertThat(audio.channels()).hasValue(6);
    assertThat(audio.bitrate()).hasValue(384_000L);

    var srtSub = probe.streams().get(2);
    assertThat(srtSub.codecType()).isEqualTo("subtitle");
    assertThat(srtSub.codec()).isEqualTo("subrip");
    assertThat(srtSub.language()).hasValue("eng");
    assertThat(srtSub.isForced()).isFalse();

    var pgsSub = probe.streams().get(3);
    assertThat(pgsSub.codec()).isEqualTo("hdmv_pgs_subtitle");
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

    var probe = service.probe(Path.of("/test/movie.mkv"));

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

    var probe = service.probe(Path.of("/test/movie.mkv"));

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

    var probe = service.probe(Path.of("/test/movie.mkv"));

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

    var probe = service.probe(Path.of("/test/movie.mkv"));

    var video = probe.streams().getFirst();
    assertThat(video.language()).isEmpty();
    assertThat(video.isDefault()).isFalse();
    assertThat(video.isForced()).isFalse();
  }

  private Process createFakeProcess(String stdout, int exitCode) {
    return new FakeProcess(stdout, exitCode);
  }
}

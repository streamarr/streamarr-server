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

    assertThatThrownBy(() -> service.probe(Path.of("/test/audio-only.mkv")))
        .isInstanceOf(TranscodeException.class)
        .hasMessageContaining("video stream");
  }

  @Test
  @DisplayName("Should throw when ffprobe process fails")
  void shouldThrowWhenFfprobeProcessFails() {
    var service = new LocalFfprobeService(objectMapper, path -> createFakeProcess("", 1));

    assertThatThrownBy(() -> service.probe(Path.of("/test/movie.mkv")))
        .isInstanceOf(FfmpegNotAvailableException.class);
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

  private Process createFakeProcess(String stdout, int exitCode) {
    return new FakeProcess(stdout, exitCode);
  }
}

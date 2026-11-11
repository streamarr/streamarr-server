package com.streamarr.server.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.support.AuthTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import tools.jackson.databind.ObjectMapper;

/**
 * Direct play end to end over HTTP (streamarr/streamarr-server#59): the GraphQL mutation chooses
 * direct play in Original for a file that the declaration covers, and the URL it answers serves the
 * file's bytes.
 */
@Tag("IntegrationTest")
@DisplayName("Direct Play Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "streaming.remote.source-root=${java.io.tmpdir}")
class DirectPlayIT extends AbstractIntegrationTest {

  private static final int SOURCE_LENGTH = 1000;
  private static final FakeTranscodeExecutor FAKE_EXECUTOR = new FakeTranscodeExecutor();

  // H.264 and AAC capability entries that apply to HLS and list Matroska, MP4 and MOV for direct
  // play, with the avc1 sample entry for MP4 and MOV.
  private static final String CAPABILITIES =
      """
      {
        video: [{
          codecFamily: H264
          codecProfiles: [{profile: H264_HIGH, maxLevel: 52}]
          maxBitDepth: 8, maxWidth: 3840, maxHeight: 2160, maxFrameRate: 60
          compatibleBases: [SDR], dolbyVisionProfiles: [], hdr10Plus: false
          hls: true, directPlayContainers: [MATROSKA, MP4, MOV], directPlaySampleEntries: [AVC1]
        }]
        audio: [{codec: AAC, maxChannels: 2, hls: true, directPlayContainers: [MATROSKA, MP4, MOV]}]
        selectsAudioTrackInDirectPlay: false
      }""";

  @LocalServerPort private int port;

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private MediaFileContainerInfoRepository probeResults;
  @Autowired private RuntimeStreamSessionRegistry runtimeRegistry;
  @Autowired private ObjectMapper objectMapper;

  @TestBean TranscodeExecutor transcodeExecutor;

  @TempDir private Path directory;

  private final HttpClient client = HttpClient.newHttpClient();
  private AuthTestSupport.TestIdentity identity;
  private byte[] sourceBytes;
  private MediaFile mediaFile;

  static TranscodeExecutor transcodeExecutor() {
    return FAKE_EXECUTOR;
  }

  @BeforeEach
  void setUp() throws Exception {
    identity = authTestSupport.createIdentity();
    sourceBytes = new byte[SOURCE_LENGTH];
    for (var index = 0; index < SOURCE_LENGTH; index++) {
      sourceBytes[index] = (byte) (index % 251);
    }

    mediaFile = probedSource("movie.mkv", h264ProbeBuilder().build());
  }

  @AfterEach
  void tearDown() {
    runtimeRegistry
        .findAll()
        .forEach(session -> runtimeRegistry.removeById(session.getSessionId()));
    authTestSupport.deleteIdentity(identity);
    client.close();
  }

  @Test
  @DisplayName(
      "Should serve the requested bytes from a direct-play URL when Original covers the file")
  void shouldServeTheRequestedBytesFromADirectPlayUrlWhenOriginalCoversTheFile() throws Exception {
    var jobsBefore = FAKE_EXECUTOR.getStartedRequests().size();

    var streamSession = createPlaybackSession(mediaFile, VideoQuality.ORIGINAL);

    assertThat(streamSession.read("$.__typename", String.class))
        .isEqualTo("DirectPlayStreamSession");
    var response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:%d%s"
                            .formatted(port, streamSession.read("$.streamUrl", String.class))))
                .header("Range", "bytes=0-99")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(response.statusCode()).isEqualTo(206);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("video/x-matroska");
    assertThat(response.body()).isEqualTo(Arrays.copyOfRange(sourceBytes, 0, 100));
    assertThat(FAKE_EXECUTOR.getStartedRequests()).hasSize(jobsBefore);
  }

  // The major brand tells MP4 from MOV, and the response's media type follows the source
  // container, never the file name (ADR 0045).
  @ParameterizedTest(name = "major brand \"{0}\"")
  @CsvSource(
      delimiter = '|',
      value = {"isom | video/mp4", "'qt  ' | video/quicktime"})
  @DisplayName(
      "Should serve an MP4 or MOV file as its brand's container when Original covers the file")
  void shouldServeAnMp4OrMovFileAsItsBrandsContainerWhenOriginalCoversTheFile(
      String majorBrand, String mediaType) throws Exception {
    var isoProbe =
        h264ProbeBuilder()
            .containerFormat(Optional.of("mov,mp4,m4a,3gp,3g2,mj2"))
            .majorBrand(Optional.of(majorBrand))
            .videoPlaybackFacts(
                Optional.of(h264FactsBuilder().sampleEntry(Optional.of("avc1")).build()))
            .build();
    var isoFile = probedSource("movie.bin", isoProbe);

    var streamSession = createPlaybackSession(isoFile, VideoQuality.ORIGINAL);

    assertThat(streamSession.read("$.__typename", String.class))
        .isEqualTo("DirectPlayStreamSession");
    var response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:%d%s"
                            .formatted(port, streamSession.read("$.streamUrl", String.class))))
                .header("Range", "bytes=0-99")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(response.statusCode()).isEqualTo(206);
    assertThat(response.headers().firstValue("Content-Type")).hasValue(mediaType);
    assertThat(response.body()).isEqualTo(Arrays.copyOfRange(sourceBytes, 0, 100));
  }

  @Test
  @DisplayName("Should play over HLS when Auto plays the same covered file")
  void shouldPlayOverHlsWhenAutoPlaysTheSameCoveredFile() throws Exception {
    var streamSession = createPlaybackSession(mediaFile, VideoQuality.AUTO);

    assertThat(streamSession.read("$.__typename", String.class)).isEqualTo("HlsStreamSession");
    assertThat(streamSession.read("$.streamUrl", String.class)).contains("/multivariant.m3u8?t=");
  }

  // A matched media file whose source holds the test's bytes, with the probe's outcome stored. The
  // server reads only outcomes up to the version it requests, which stays 6 until
  // streamarr/streamarr-server#728 raises it to 7, so the MP4 and MOV cases store version 7's
  // sample entry and major brand under the current version.
  private MediaFile probedSource(String filename, MediaProbe probe) throws Exception {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var path = Files.write(directory.resolve(filename), sourceBytes);
    var source =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .filepathUri(FilepathCodec.encode(path))
                .filename(filename)
                .status(MediaFileStatus.MATCHED)
                .size((long) SOURCE_LENGTH)
                .libraryId(library.getId())
                .build());
    var snapshot =
        new SourceFileSnapshot(Files.size(path), Files.getLastModifiedTime(path).toInstant());
    assertThat(
            probeResults.publish(
                ProbePublication.builder()
                    .mediaFileId(source.getId())
                    .snapshot(snapshot)
                    .probeVersion(ProbeVersion.CURRENT)
                    .outcome(ProbeFixture.completeProbe(probe))
                    .build()))
        .isTrue();
    return source;
  }

  private DocumentContext createPlaybackSession(MediaFile source, VideoQuality quality)
      throws Exception {
    var mutation =
        """
        mutation {
          createPlaybackSession(input: {
            mediaFileId: "%s", networkRoute: INTERNET, options: {quality: %s}, capabilities: %s
          }) {
            playbackSession {
              currentFormatAttempt {
                __typename
                ... on ReadyPlaybackFormatAttempt { streamSession { __typename id streamUrl } }
              }
            }
            userErrors { __typename ... on MutationError { message } }
          }
        }
        """
            .formatted(source.getId(), quality, CAPABILITIES);
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:%d/graphql".formatted(port)))
                .header("Authorization", "Bearer " + authTestSupport.profileBearer(identity))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(Map.of("query", mutation))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    var body = JsonPath.parse(response.body());
    assertThat(body.read("$.data.createPlaybackSession.userErrors", List.class)).isEmpty();
    return JsonPath.parse(
        (Object)
            body.read(
                "$.data.createPlaybackSession.playbackSession.currentFormatAttempt.streamSession"));
  }

  private static VideoPlaybackFacts.VideoPlaybackFactsBuilder h264FactsBuilder() {
    return VideoPlaybackFactsFixture.sdrH264FactsBuilder()
        .streamIndex(0)
        .videoProperties(
            Optional.of(VideoPlaybackFactsFixture.h264HighPropertiesBuilder().build()));
  }

  // An H.264 and stereo AAC file in Matroska.
  private static MediaProbe.MediaProbeBuilder h264ProbeBuilder() {
    return StreamSessionFixture.defaultProbeBuilder()
        .containerFormat(Optional.of("matroska,webm"))
        .audioChannels(OptionalInt.of(2))
        .videoPlaybackFacts(Optional.of(h264FactsBuilder().build()));
  }
}

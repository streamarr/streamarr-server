package com.streamarr.server.services.streaming.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.services.streaming.HlsPlaylistService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Spec-cited HLS playlist structure suite. Each case parses a playlist actually produced by {@link
 * HlsPlaylistService} and asserts a structural obligation from RFC 8216 (and its 8216bis revision),
 * citing the relevant section in its {@code @DisplayName} and an inline comment.
 *
 * <p>Tagged {@code HlsConformance} in addition to {@code UnitTest} so it runs inside the standard
 * {@code ./mvnw verify} CI gate. ADR 0019 records the intentional advertise-ahead deviation;
 * delivery and recovery behavior is tracked separately because a generated playlist alone cannot
 * prove that its segment URIs are downloadable.
 */
@Tag("UnitTest")
@Tag("HlsConformance")
@DisplayName("HLS RFC 8216 Playlist Structure")
class HlsPlaylistStructureTest {

  private static final String TOKEN = "conformance-token";
  private static final int TARGET_SEGMENT_SECONDS = 6;

  private HlsPlaylistService service;

  @BeforeEach
  void setUp() {
    var properties =
        StreamingProperties.builder()
            .targetSegmentDuration(Duration.ofSeconds(TARGET_SEGMENT_SECONDS))
            .sessionTimeout(Duration.ofSeconds(60))
            .build();
    service = new HlsPlaylistService(properties);
  }

  private StreamSession mediaSession(ContainerFormat container, int durationSeconds) {
    return StreamSessionFixture.sessionWithDurationBuilder(durationSeconds)
        .transcodeDecision(
            StreamSessionFixture.fullTranscodeDecision(
                container == ContainerFormat.FMP4 ? "av1" : "h264", container))
        .build();
  }

  private StreamSession abrSession() {
    var variants =
        List.of(
            StreamSessionFixture.defaultVariantBuilder()
                .width(1920)
                .height(1080)
                .videoBitrate(5_000_000L)
                .label("1080p")
                .build(),
            StreamSessionFixture.defaultVariantBuilder()
                .width(1280)
                .height(720)
                .videoBitrate(3_000_000L)
                .label("720p")
                .build(),
            StreamSessionFixture.defaultVariantBuilder()
                .width(854)
                .height(480)
                .videoBitrate(1_500_000L)
                .label("480p")
                .build());

    return StreamSessionFixture.sessionWithDurationBuilder(120)
        .transcodeDecision(
            StreamSessionFixture.fullTranscodeDecision("h264", ContainerFormat.MPEGTS))
        .variants(variants)
        .build();
  }

  @Nested
  @DisplayName("Media playlist structure")
  class MediaPlaylistStructure {

    // RFC 8216 §4.3.1.1: the EXTM3U tag "MUST be the first line of every Media Playlist and every
    // Master Playlist" and indicates the file is an Extended M3U playlist.
    @Test
    @DisplayName("Should start with EXTM3U first line when generating media playlist (§4.3.1.1)")
    void shouldStartWithExtm3uFirstLineWhenGeneratingMediaPlaylist() {
      var playlist = MediaPlaylist.parse(generateMpegtsMediaPlaylist());

      assertThat(playlist.firstLine()).isEqualTo("#EXTM3U");
    }

    // RFC 8216 §7 (Protocol Version Compatibility): a playlist MUST declare an EXT-X-VERSION no
    // lower than the highest version its tags require — floating-point EXTINF requires >= 3, and
    // EXT-X-MAP in a Media Playlist without EXT-X-I-FRAMES-ONLY requires >= 6.
    @ParameterizedTest(name = "{0}")
    @EnumSource(ContainerFormat.class)
    @DisplayName("Should declare version at least what its tags require (§7)")
    void shouldDeclareVersionAtLeastWhatTagsRequireWhenGeneratingMediaPlaylist(
        ContainerFormat container) {
      var playlist =
          MediaPlaylist.parse(service.generateMediaPlaylist(mediaSession(container, 30), TOKEN));

      assertThat(playlist.version()).isPresent();
      assertThat(playlist.version().getAsInt())
          .as("declared EXT-X-VERSION must satisfy the tags used")
          .isGreaterThanOrEqualTo(playlist.requiredVersion());
    }

    // RFC 8216 §4.3.3.1: the EXTINF duration of each Media Segment, "rounded to the nearest
    // integer, MUST be less than or equal to the target duration."
    @Test
    @DisplayName("Should keep every EXTINF within TARGETDURATION when rounded (§4.3.3.1)")
    void shouldKeepEveryExtinfWithinTargetDurationWhenRounded() {
      // 16s / 6s target yields two full 6s segments and a short 4s tail — exercises both.
      var playlist = MediaPlaylist.parse(generateMpegtsMediaPlaylist(16));

      assertThat(playlist.targetDuration()).isPresent();
      var targetDuration = playlist.targetDuration().getAsInt();
      assertThat(playlist.extinfDurations()).isNotEmpty();
      for (var extinf : playlist.extinfDurations()) {
        assertThat(Math.round(extinf))
            .as("EXTINF %s rounds within TARGETDURATION %s", extinf, targetDuration)
            .isLessThanOrEqualTo(targetDuration);
      }
    }

    // RFC 8216 §4.3.3.2: EXT-X-MEDIA-SEQUENCE indicates the Media Sequence Number of the first
    // segment; when absent it defaults to 0, but Streamarr declares it explicitly.
    @Test
    @DisplayName("Should declare EXT-X-MEDIA-SEQUENCE when generating media playlist (§4.3.3.2)")
    void shouldDeclareMediaSequenceWhenGeneratingMediaPlaylist() {
      var playlist = MediaPlaylist.parse(generateMpegtsMediaPlaylist());

      assertThat(playlist.hasMediaSequence()).isTrue();
    }

    // RFC 8216 §4.3.3.5: EXT-X-PLAYLIST-TYPE:VOD means "the Playlist file MUST NOT change";
    // §4.3.3.4: EXT-X-ENDLIST indicates no more segments will be added. A VOD playlist is therefore
    // immutable — regenerating it from the same input MUST yield byte-identical output.
    @Test
    @DisplayName("Should be an immutable VOD playlist ending in ENDLIST (§4.3.3.5)")
    void shouldBeImmutableVodPlaylistEndingInEndListWhenGeneratingMediaPlaylist() {
      var session = mediaSession(ContainerFormat.MPEGTS, 30);

      var first = service.generateMediaPlaylist(session, TOKEN);
      var second = service.generateMediaPlaylist(session, TOKEN);
      var playlist = MediaPlaylist.parse(first);

      assertThat(playlist.hasPlaylistTypeVod()).isTrue();
      assertThat(playlist.endsWithEndList()).isTrue();
      assertThat(second)
          .as("regenerating a VOD playlist from the same input must be byte-identical")
          .isEqualTo(first);
    }

    // RFC 8216 §4.3.2.5: EXT-X-MAP specifies the Media Initialization Section required to parse the
    // fMP4 Media Segments that follow it; an fMP4 media playlist MUST declare one.
    @Test
    @DisplayName("Should declare EXT-X-MAP init segment for fMP4 media playlist (§4.3.2.5)")
    void shouldDeclareExtXMapInitSegmentWhenContainerIsFmp4() {
      var playlist =
          MediaPlaylist.parse(
              service.generateMediaPlaylist(mediaSession(ContainerFormat.FMP4, 30), TOKEN));

      assertThat(playlist.hasMap()).isTrue();
      assertThat(playlist.mapUri()).isPresent();
      assertThat(playlist.mapUri().get()).startsWith("init.mp4");
    }

    private String generateMpegtsMediaPlaylist() {
      return generateMpegtsMediaPlaylist(30);
    }

    private String generateMpegtsMediaPlaylist(int durationSeconds) {
      return service.generateMediaPlaylist(
          mediaSession(ContainerFormat.MPEGTS, durationSeconds), TOKEN);
    }
  }

  @Nested
  @DisplayName("Multivariant playlist structure")
  class MultivariantPlaylistStructure {

    // RFC 8216 §4.3.4.2: every EXT-X-STREAM-INF tag MUST include the BANDWIDTH attribute (the peak
    // segment bit rate); AVERAGE-BANDWIDTH, when present, is the average segment bit rate and can
    // never exceed the peak BANDWIDTH.
    @Test
    @DisplayName("Should give every STREAM-INF a BANDWIDTH >= AVERAGE-BANDWIDTH (§4.3.4.2)")
    void shouldGiveEveryStreamInfBandwidthNotBelowAverageBandwidth() {
      var playlist =
          MultivariantPlaylist.parse(service.generateMultivariantPlaylist(abrSession(), TOKEN));

      assertThat(playlist.streamInfs()).isNotEmpty();
      for (var streamInf : playlist.streamInfs()) {
        assertThat(streamInf.bandwidth())
            .as("BANDWIDTH is required on every EXT-X-STREAM-INF")
            .isPresent();
        assertThat(streamInf.bandwidth().getAsLong()).isPositive();
        streamInf
            .averageBandwidth()
            .ifPresent(
                average ->
                    assertThat(average)
                        .as("AVERAGE-BANDWIDTH must not exceed peak BANDWIDTH")
                        .isLessThanOrEqualTo(streamInf.bandwidth().getAsLong()));
      }
    }
  }

  /** Minimal parser over the media playlist text Streamarr produces, for spec assertions. */
  private record MediaPlaylist(List<String> lines) {

    private static final Pattern EXTINF = Pattern.compile("^#EXTINF:([0-9.]+),.*$");
    private static final Pattern MAP_URI = Pattern.compile("^#EXT-X-MAP:URI=\"([^\"]*)\".*$");

    static MediaPlaylist parse(String raw) {
      return new MediaPlaylist(raw.lines().toList());
    }

    String firstLine() {
      return lines.getFirst();
    }

    OptionalInt version() {
      return intTag("#EXT-X-VERSION:");
    }

    OptionalInt targetDuration() {
      return intTag("#EXT-X-TARGETDURATION:");
    }

    boolean hasMediaSequence() {
      return lines.stream().anyMatch(line -> line.startsWith("#EXT-X-MEDIA-SEQUENCE:"));
    }

    boolean hasPlaylistTypeVod() {
      return lines.contains("#EXT-X-PLAYLIST-TYPE:VOD");
    }

    boolean endsWithEndList() {
      return lines.getLast().equals("#EXT-X-ENDLIST");
    }

    boolean hasMap() {
      return lines.stream().anyMatch(line -> line.startsWith("#EXT-X-MAP:"));
    }

    Optional<String> mapUri() {
      return lines.stream()
          .map(MAP_URI::matcher)
          .filter(Matcher::matches)
          .map(matcher -> matcher.group(1))
          .findFirst();
    }

    List<Double> extinfDurations() {
      return lines.stream()
          .map(EXTINF::matcher)
          .filter(Matcher::matches)
          .map(matcher -> Double.parseDouble(matcher.group(1)))
          .toList();
    }

    /** Lowest EXT-X-VERSION the tags actually present require, per RFC 8216 §7. */
    int requiredVersion() {
      var required = 1;
      if (lines.stream().anyMatch(line -> line.startsWith("#EXTINF:") && line.contains("."))) {
        required = Math.max(required, 3);
      }
      if (hasMap()) {
        required = Math.max(required, 6);
      }
      return required;
    }

    private OptionalInt intTag(String prefix) {
      return lines.stream()
          .filter(line -> line.startsWith(prefix))
          .mapToInt(line -> Integer.parseInt(line.substring(prefix.length()).trim()))
          .findFirst();
    }
  }

  /** Minimal parser over the multivariant playlist text Streamarr produces. */
  private record MultivariantPlaylist(List<StreamInf> streamInfs) {

    static MultivariantPlaylist parse(String raw) {
      var streamInfs =
          raw.lines()
              .filter(line -> line.startsWith("#EXT-X-STREAM-INF:"))
              .map(StreamInf::parse)
              .toList();
      return new MultivariantPlaylist(streamInfs);
    }
  }

  private record StreamInf(OptionalLong bandwidth, OptionalLong averageBandwidth) {

    private static final Pattern BANDWIDTH = Pattern.compile("(?<![-A-Z])BANDWIDTH=(\\d+)");
    private static final Pattern AVERAGE_BANDWIDTH = Pattern.compile("AVERAGE-BANDWIDTH=(\\d+)");

    static StreamInf parse(String line) {
      return new StreamInf(match(BANDWIDTH, line), match(AVERAGE_BANDWIDTH, line));
    }

    private static OptionalLong match(Pattern pattern, String line) {
      var matcher = pattern.matcher(line);
      if (!matcher.find()) {
        return OptionalLong.empty();
      }
      return OptionalLong.of(Long.parseLong(matcher.group(1)));
    }
  }
}

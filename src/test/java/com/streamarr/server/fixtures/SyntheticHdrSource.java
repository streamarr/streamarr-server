package com.streamarr.server.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.ChromaticityCoordinates;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.ContentLightLevel;
import com.streamarr.server.domain.streaming.MasteringDisplayColorVolume;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * A synthetic HEVC Main 10 source that the worker image's FFmpeg encodes with libx265 at test time,
 * so no media bytes live in the repository: {@value #SOURCE_SECONDS} s of 128x72 at 24 fps with a
 * keyframe every {@value #KEYFRAME_SECONDS} s, BT.2020 primaries and the PQ or HLG transfer, and an
 * access unit delimiter in front of each picture. With static HDR metadata, libx265 writes the
 * mastering display and content light level messages into each keyframe's sample.
 *
 * <p>A Dolby Vision source gets an RPU, a NAL unit of type 62, behind each picture's slice as the
 * sample's last NAL unit, and the configuration record in its MP4 sample entry. With
 * enhancement-layer units, each sample also holds NAL units of type 63 on both sides of its slice,
 * as a single-track profile 7 stream does. No unit holds real RPU or enhancement-layer data: an RPU
 * here opens with rpu_nal_prefix 25 and rpu_type 0, which no player or probe reads as an RPU
 * header. The units check a copy's plumbing, not a picture.
 *
 * <p>With numbered keyframe messages, a prefix SEI message of the user data unregistered type sits
 * in front of each keyframe's slice and carries the keyframe's number, so a record that a muxer
 * built from the first sample of a job attempt would differ between attempts that start at
 * different keyframes. The source goes into MPEG-TS instead of MP4 when its parameter sets and SEI
 * messages must stay in its samples, as every HEVC stream in MPEG-TS keeps them.
 */
@Builder(builderMethodName = "hevcSourceBuilder")
public record SyntheticHdrSource(
    @NonNull String name,
    @NonNull CompatibleBase compatibleBase,
    boolean staticHdrMetadata,
    @NonNull Optional<DolbyVisionConfigurationBox> dolbyVisionRecord,
    boolean enhancementLayerUnits,
    boolean numberedKeyframeMessages,
    boolean mpegTsContainer) {

  public static final int SOURCE_SECONDS = 12;
  public static final int KEYFRAME_SECONDS = 2;
  public static final int FRAME_RATE = 24;

  /** The BT.2020 primaries and D65 white point of a 1,000 cd/m² mastering display. */
  public static final MasteringDisplayColorVolume MASTERING_DISPLAY =
      MasteringDisplayColorVolume.builder()
          .green(new ChromaticityCoordinates(8500, 39850))
          .blue(new ChromaticityCoordinates(6550, 2300))
          .red(new ChromaticityCoordinates(35400, 14600))
          .whitePoint(new ChromaticityCoordinates(15635, 16450))
          .maxLuminance(10_000_000)
          .minLuminance(50)
          .build();

  public static final ContentLightLevel CONTENT_LIGHT_LEVEL = new ContentLightLevel(1000, 400);

  private static final int FIRST_NON_PICTURE_TYPE = 32;
  // ITU-T H.265 table 7-1: the IRAP pictures, which start a keyframe's sample.
  private static final int FIRST_KEYFRAME_TYPE = 16;
  private static final int LAST_KEYFRAME_TYPE = 21;
  private static final int PREFIX_SEI_TYPE = 39;
  private static final int USER_DATA_UNREGISTERED = 5;
  // An arbitrary UUID without zero bytes, so no emulation prevention byte is needed.
  private static final byte[] KEYFRAME_NUMBER_UUID =
      HexFormat.of().parseHex("5354524541D4A3525246524D4E554D42");
  private static final int RPU_TYPE = 62;
  private static final int ENHANCEMENT_LAYER_TYPE = 63;
  private static final List<String> SAMPLE_ENTRY_PATH =
      List.of("trak", "mdia", "minf", "stbl", "stsd", "hvc1");

  // Lombok builder default — its generated setter assigns it
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class SyntheticHdrSourceBuilder {
    private Optional<DolbyVisionConfigurationBox> dolbyVisionRecord = Optional.empty();
  }

  /** Writes the source under the worker's source root, where the worker reads it. */
  public Path writtenBy(WorkerContainerFixture worker, Path sourceRoot) throws Exception {
    var elementary = worker.generateMedia(sourceRoot.resolve(name + ".hevc"), encodeArguments());
    if (dolbyVisionRecord.isPresent() || numberedKeyframeMessages) {
      elementary =
          Files.write(
              sourceRoot.resolve(name + "-with-added-units.hevc"),
              withAddedUnits(Files.readAllBytes(elementary)));
    }

    var muxing =
        new ArrayList<>(
            List.of(
                "-f",
                "hevc",
                "-framerate",
                String.valueOf(FRAME_RATE),
                "-i",
                "/media/" + sourceRoot.relativize(elementary),
                "-c",
                "copy"));
    muxing.addAll(
        mpegTsContainer ? List.of("-f", "mpegts") : List.of("-tag:v", "hvc1", "-f", "mp4"));
    var muxed =
        worker.generateMedia(
            sourceRoot.resolve(name + (mpegTsContainer ? ".ts" : "-muxed.mp4")), muxing);
    if (dolbyVisionRecord.isEmpty()) {
      return muxed;
    }

    assertThat(mpegTsContainer).as("a Dolby Vision record goes into an MP4 sample entry").isFalse();
    return Files.write(
        sourceRoot.resolve(name + ".mp4"),
        withRecord(Files.readAllBytes(muxed), dolbyVisionRecord.get().box()));
  }

  private List<String> encodeArguments() {
    var parameters =
        new StringBuilder(
                "keyint=%d:min-keyint=%d".formatted(keyframeInterval(), keyframeInterval()))
            .append(":scenecut=0:bframes=0:aud=1:log-level=error")
            .append(":colorprim=bt2020:colormatrix=bt2020nc:transfer=")
            .append(transfer());
    if (staticHdrMetadata) {
      parameters
          .append(":master-display=")
          .append(x265MasteringDisplay())
          .append(
              ":max-cll=%d,%d"
                  .formatted(
                      CONTENT_LIGHT_LEVEL.maxContentLightLevel(),
                      CONTENT_LIGHT_LEVEL.maxFrameAverageLightLevel()));
    }

    return List.of(
        "-f",
        "lavfi",
        "-i",
        "testsrc2=size=128x72:rate=%d:duration=%d".formatted(FRAME_RATE, SOURCE_SECONDS),
        "-c:v",
        "libx265",
        "-preset",
        "ultrafast",
        "-pix_fmt",
        "yuv420p10le",
        "-x265-params",
        parameters.toString(),
        "-f",
        "hevc");
  }

  private static int keyframeInterval() {
    return FRAME_RATE * KEYFRAME_SECONDS;
  }

  private String transfer() {
    return switch (compatibleBase) {
      case PQ -> "smpte2084";
      case HLG -> "arib-std-b67";
      case SDR, NONE, UNSPECIFIED ->
          throw new IllegalArgumentException("A synthetic HDR source is PQ or HLG");
    };
  }

  // x265 takes the primaries as green, blue and red, then the white point and the luminances.
  private static String x265MasteringDisplay() {
    var display = MASTERING_DISPLAY;
    return "G(%d,%d)B(%d,%d)R(%d,%d)WP(%d,%d)L(%d,%d)"
        .formatted(
            display.green().x(),
            display.green().y(),
            display.blue().x(),
            display.blue().y(),
            display.red().x(),
            display.red().y(),
            display.whitePoint().x(),
            display.whitePoint().y(),
            display.maxLuminance(),
            display.minLuminance());
  }

  private byte[] withAddedUnits(byte[] annexB) {
    var written = new ByteArrayOutputStream();
    var picture = 0;
    var keyframe = 0;
    for (var unit : nalUnitsOf(annexB)) {
      var type = (Byte.toUnsignedInt(unit[0]) >> 1) & 0x3F;
      if (type >= FIRST_NON_PICTURE_TYPE) {
        writeAnnexB(written, unit);
        continue;
      }

      if (numberedKeyframeMessages && isKeyframe(type)) {
        writeAnnexB(written, keyframeNumberMessage(keyframe));
      }

      writePicture(written, unit, picture);
      picture++;
      if (isKeyframe(type)) {
        keyframe++;
      }
    }

    return written.toByteArray();
  }

  private static boolean isKeyframe(int type) {
    return type >= FIRST_KEYFRAME_TYPE && type <= LAST_KEYFRAME_TYPE;
  }

  // One user data unregistered message (ITU-T H.265 D.2.7) of the UUID and the keyframe's number
  // from 1, then the RBSP stop bit.
  private static byte[] keyframeNumberMessage(int keyframe) {
    return ByteBuffer.allocate(2 + 2 + KEYFRAME_NUMBER_UUID.length + 2)
        .put((byte) (PREFIX_SEI_TYPE << 1))
        .put((byte) 0x01)
        .put((byte) USER_DATA_UNREGISTERED)
        .put((byte) (KEYFRAME_NUMBER_UUID.length + 1))
        .put(KEYFRAME_NUMBER_UUID)
        .put((byte) (keyframe + 1))
        .put((byte) 0x80)
        .array();
  }

  private void writePicture(ByteArrayOutputStream written, byte[] slice, int picture) {
    if (dolbyVisionRecord.isEmpty()) {
      writeAnnexB(written, slice);
      return;
    }

    if (enhancementLayerUnits) {
      writeAnnexB(written, unitOf(ENHANCEMENT_LAYER_TYPE, picture));
    }

    writeAnnexB(written, slice);
    if (enhancementLayerUnits) {
      writeAnnexB(written, unitOf(ENHANCEMENT_LAYER_TYPE, picture));
      writeAnnexB(written, unitOf(ENHANCEMENT_LAYER_TYPE, picture));
    }

    writeAnnexB(written, rpuOf(picture));
  }

  private static List<byte[]> nalUnitsOf(byte[] annexB) {
    var starts = new ArrayList<Integer>();
    for (var index = 0; index + 2 < annexB.length; index++) {
      if (annexB[index] == 0 && annexB[index + 1] == 0 && annexB[index + 2] == 1) {
        starts.add(index + 3);
      }
    }

    var units = new ArrayList<byte[]>();
    for (var index = 0; index < starts.size(); index++) {
      var end = index + 1 < starts.size() ? starts.get(index + 1) - 3 : annexB.length;
      while (end > starts.get(index) && annexB[end - 1] == 0) {
        end--;
      }

      units.add(Arrays.copyOfRange(annexB, starts.get(index), end));
    }

    assertThat(units).as("the NAL units of the encode").isNotEmpty();
    return units;
  }

  private static byte[] unitOf(int type, int picture) {
    return new byte[] {(byte) (type << 1), 0x01, 0x02, (byte) picture, 0x7F};
  }

  private static byte[] rpuOf(int picture) {
    return new byte[] {(byte) (RPU_TYPE << 1), 0x01, 0x19, 0x00, (byte) picture, 0x7F};
  }

  private static void writeAnnexB(ByteArrayOutputStream target, byte[] unit) {
    target.writeBytes(new byte[] {0, 0, 0, 1});
    target.writeBytes(unit);
  }

  // The MP4 with the record appended to its hvc1 sample entry. FFmpeg writes the moov behind the
  // mdat, so no chunk offset moves.
  private static byte[] withRecord(byte[] mp4, byte[] recordBox) {
    var rebuilt = new ByteArrayOutputStream();
    var position = 0;
    var passedMdat = false;
    while (position < mp4.length) {
      var size = ByteBuffer.wrap(mp4, position, 4).getInt();
      var bytes = Arrays.copyOfRange(mp4, position, position + size);
      var type = typeOf(bytes);
      passedMdat |= type.equals("mdat");
      if (type.equals("moov")) {
        assertThat(passedMdat).as("the moov follows the mdat").isTrue();
        bytes = appended(bytes, SAMPLE_ENTRY_PATH, recordBox);
      }

      rebuilt.writeBytes(bytes);
      position += size;
    }

    return rebuilt.toByteArray();
  }

  // The box with the child appended to the last box of the path below it.
  private static byte[] appended(byte[] box, List<String> path, byte[] child) {
    var type = typeOf(box);
    var fields =
        switch (type) {
          case "stsd" -> 8;
          case "hvc1" -> 78;
          default -> 0;
        };
    var body = new ByteArrayOutputStream();
    body.write(box, 8, fields);
    var position = 8 + fields;
    while (position < box.length) {
      var size = ByteBuffer.wrap(box, position, 4).getInt();
      var nested = Arrays.copyOfRange(box, position, position + size);
      if (!path.isEmpty() && typeOf(nested).equals(path.getFirst())) {
        nested = appended(nested, path.subList(1, path.size()), child);
      }

      body.writeBytes(nested);
      position += size;
    }

    if (path.isEmpty()) {
      body.writeBytes(child);
    }

    return ByteBuffer.allocate(8 + body.size())
        .putInt(8 + body.size())
        .put(type.getBytes(StandardCharsets.US_ASCII))
        .put(body.toByteArray())
        .array();
  }

  private static String typeOf(byte[] box) {
    return new String(box, 4, 4, StandardCharsets.US_ASCII);
  }
}

package com.streamarr.server.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import lombok.Builder;

/**
 * The fields of a Dolby Vision configuration record that a copy's checks read, as Dolby's ISO base
 * media file format specification lays the record out: version 1.0, the profile (7 bits), the level
 * (6 bits), the RPU, enhancement-layer and base-layer flags, the base layer's compatibility
 * identifier (4 bits), the metadata compression (2 bits), and reserved zeros to 24 bytes. Every
 * record here declares an RPU and a base layer, and no enhancement layer.
 */
@Builder
public record DolbyVisionConfigurationBox(
    int profile, int level, int compatibilityId, int compression) {

  private static final int RECORD_BYTES = 24;
  private static final int RPU_AND_BASE_LAYER = 0x05;

  /** The box type that the profile takes: dvcC up to profile 7, dvvC for profiles 8 to 10. */
  public String boxType() {
    return profile <= 7 ? "dvcC" : "dvvC";
  }

  /** The whole box, its size and type included. */
  public byte[] box() {
    return ByteBuffer.allocate(8 + RECORD_BYTES)
        .putInt(8 + RECORD_BYTES)
        .put(boxType().getBytes(StandardCharsets.US_ASCII))
        .put((byte) 1)
        .put((byte) 0)
        .put((byte) ((profile << 1) | (level >> 5)))
        .put((byte) (((level & 0x1F) << 3) | RPU_AND_BASE_LAYER))
        .put((byte) ((compatibilityId << 4) | (compression << 2)))
        .array();
  }

  /** The record of a box's body, checked against the type that its profile takes. */
  static DolbyVisionConfigurationBox read(String type, ByteBuffer body) {
    assertThat(body.remaining()).as("the %s body", type).isEqualTo(RECORD_BYTES);
    var profileAndLevel = Byte.toUnsignedInt(body.get(2));
    var levelAndFlags = Byte.toUnsignedInt(body.get(3));
    var compatibilityAndCompression = Byte.toUnsignedInt(body.get(4));
    var fields =
        DolbyVisionConfigurationBox.builder()
            .profile(profileAndLevel >> 1)
            .level(((profileAndLevel & 0x01) << 5) | (levelAndFlags >> 3))
            .compatibilityId(compatibilityAndCompression >> 4)
            .compression((compatibilityAndCompression >> 2) & 0x03)
            .build();
    assertThat(type).as("the box type of %s", fields).isEqualTo(fields.boxType());
    assertThat(levelAndFlags & 0x07)
        .as("the layer flags of %s", fields)
        .isEqualTo(RPU_AND_BASE_LAYER);
    return fields;
  }
}

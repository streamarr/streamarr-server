package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("FFmpeg Redistribution Tests")
class FfmpegRedistributionTest {

  private static final Path NOTICES = Path.of("buildpacks/ffmpeg/notices");

  @Test
  @DisplayName("Should preserve hashed input bytes across platform checkouts")
  void shouldPreserveHashedInputBytesAcrossPlatformCheckouts() throws IOException {
    var attributes = Files.readString(Path.of(".gitattributes"));

    assertThat(attributes)
        .contains(
            "buildpacks/ffmpeg/notices/** -text", "buildpacks/ffmpeg/LICENSE.txt -text",
            "buildpacks/ffmpeg/SOURCE.txt text eol=lf",
                "buildpacks/ffmpeg/ffmpeg.lock text eol=lf");
  }

  @Test
  @DisplayName("Should retain every inventoried notice with its reviewed contents")
  void shouldRetainEveryInventoriedNoticeWithItsReviewedContents() throws Exception {
    var inventory = new ObjectMapper().readTree(Files.readString(NOTICES.resolve("sources.json")));
    var digest = MessageDigest.getInstance("SHA-256");

    assertThat(inventory.isArray()).isTrue();
    assertThat(inventory.isEmpty()).isFalse();
    for (var component : inventory) {
      assertThat(component.path("notices").isEmpty()).isFalse();
      for (var notice : component.path("notices")) {
        var path = NOTICES.resolve(notice.path("file").asString()).normalize();
        assertThat(path).startsWith(NOTICES).isRegularFile();
        var checksum = HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        assertThat(checksum)
            .as("Notice checksum: %s", path)
            .isEqualTo(notice.path("sha256").asString());
      }
    }
  }

  @Test
  @DisplayName("Should retain the json11 notice when shipping x265 HDR10 support")
  void shouldRetainJson11NoticeWhenShippingX265Hdr10Support() throws IOException {
    var inventory = Files.readString(NOTICES.resolve("sources.json"));

    assertThat(inventory).contains("x265/source/dynamicHDR10/json11/LICENSE.txt");
    assertThat(Files.readString(NOTICES.resolve("x265/source/dynamicHDR10/json11/LICENSE.txt")))
        .contains("Copyright (c) 2013 Dropbox, Inc.", "Permission is hereby granted");
  }

  @Test
  @DisplayName("Should provide source access for every inventoried component")
  void shouldProvideSourceAccessForEveryInventoriedComponent() throws IOException {
    var inventory = new ObjectMapper().readTree(Files.readString(NOTICES.resolve("sources.json")));
    var sourceAccess = Files.readString(Path.of("buildpacks/ffmpeg/SOURCE.txt"));

    for (var component : inventory) {
      assertThat(sourceAccess)
          .as("Source access: %s", component.path("id").asString())
          .contains(
              component.path("repository").asString(),
              component.path("revision").asString(),
              "notices/sources.json (" + component.path("id").asString() + ")");
    }

    assertThat(sourceAccess)
        .contains("Corresponding Source", "builder/patches", "git checkout --detach");
    assertThat(Files.readString(NOTICES.resolve("fdk-aac-stripped/NOTICE.txt")))
        .contains("Fraunhofer FDK AAC Codec Library", "source code", "patent");
  }
}

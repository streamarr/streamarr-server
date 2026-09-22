package com.streamarr.server.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Shared by the probes that {@code NonUtf8LocaleFilenameIT} runs inside its ASCII-locale container.
 *
 * <p>Every value is printed as base64 of its UTF-8 bytes. Under an ASCII locale {@code System.out}
 * uses {@code stdout.encoding}, which would turn the U+FFFD characters the probes exist to observe
 * into plain question marks before the test could see them.
 */
public final class NonUtf8LocaleProbeSupport {

  private NonUtf8LocaleProbeSupport() {}

  public static Path firstRegularFileUnder(Path root) throws IOException {
    try (var entries = Files.walk(root)) {
      return entries.filter(Files::isRegularFile).findFirst().orElseThrow();
    }
  }

  public static void report(String key, String value) {
    System.out.println(
        key + "=" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
  }
}

package com.streamarr.server.services.streaming.ffmpeg;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

@Slf4j
public class TranscodeCapabilityService {

  private static final Pattern HW_ENCODER_PATTERN =
      Pattern.compile("^\\s*V\\S+\\s+(\\w+_(?:nvenc|qsv|amf|vaapi|videotoolbox))\\s+");

  private static final Map<String, String> SOFTWARE_ENCODERS =
      Map.of(
          "h264", "libx264",
          "hevc", "libx265",
          "av1", "libsvtav1");

  private static final Map<String, String> CODEC_HW_PREFIX =
      Map.of(
          "h264", "h264_",
          "hevc", "hevc_",
          "av1", "av1_");

  private final String ffmpegPath;
  private final ProcessFactory processFactory;
  @Getter private boolean ffmpegAvailable;

  @Getter
  private HardwareEncodingCapability hardwareEncodingCapability =
      HardwareEncodingCapability.builder().available(false).encoders(Set.of()).build();

  public TranscodeCapabilityService(String ffmpegPath, ProcessFactory processFactory) {
    this.ffmpegPath = ffmpegPath;
    this.processFactory = processFactory;
  }

  public void detectCapabilities() {
    ffmpegAvailable = checkFfmpegVersion();
    if (!ffmpegAvailable) {
      log.warn("FFmpeg not found. Transcoding will be unavailable.");
      return;
    }

    var hwEncoders = detectHardwareEncoders();
    var hasGpu = !hwEncoders.isEmpty();
    var accelerator = hasGpu ? detectAccelerator() : "";

    hardwareEncodingCapability =
        HardwareEncodingCapability.builder()
            .available(hasGpu)
            .encoders(hwEncoders)
            .accelerator(accelerator)
            .build();

    log.info(
        "FFmpeg capabilities: GPU={}, encoders={}, accelerator={}",
        hasGpu,
        hwEncoders,
        accelerator);
  }

  public String resolveEncoder(String codecFamily) {
    var softwareDefault = SOFTWARE_ENCODERS.getOrDefault(codecFamily, "libx264");

    if (!hardwareEncodingCapability.available()) {
      return softwareDefault;
    }

    var prefix = CODEC_HW_PREFIX.get(codecFamily);
    if (prefix == null) {
      return softwareDefault;
    }

    for (var encoder : hardwareEncodingCapability.encoders()) {
      if (encoder.startsWith(prefix)) {
        return encoder;
      }
    }

    return softwareDefault;
  }

  private boolean checkFfmpegVersion() {
    try {
      var process = processFactory.create(new String[] {ffmpegPath, "-version"});
      var exitCode = process.waitFor();
      return exitCode == 0;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("FFmpeg version check interrupted", e);
      return false;
    } catch (Exception e) {
      log.debug("FFmpeg version check failed", e);
      return false;
    }
  }

  private Set<String> detectHardwareEncoders() {
    try {
      var process = processFactory.create(new String[] {ffmpegPath, "-encoders"});
      var encoders = new HashSet<String>();

      try (var reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          var matcher = HW_ENCODER_PATTERN.matcher(line);
          if (matcher.find()) {
            encoders.add(matcher.group(1));
          }
        }
      }

      process.waitFor();
      return Set.copyOf(encoders);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("Hardware encoder detection interrupted", e);
      return Set.of();
    } catch (Exception e) {
      log.debug("Hardware encoder detection failed", e);
      return Set.of();
    }
  }

  private String detectAccelerator() {
    try {
      var process = processFactory.create(new String[] {ffmpegPath, "-hwaccels"});
      var result = getStringBuilder(process);

      process.waitFor();
      return result.toString();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("Accelerator detection interrupted", e);
      return "";
    } catch (Exception e) {
      log.debug("Accelerator detection failed", e);
      return "";
    }
  }

  private static @NonNull StringBuilder getStringBuilder(Process process) throws IOException {
    var result = new StringBuilder();

    try (var reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        var trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("Hardware")) {
          continue;
        }
        if (!result.isEmpty()) {
          result.append(",");
        }
        result.append(trimmed);
      }
    }
    return result;
  }

  @FunctionalInterface
  public interface ProcessFactory {
    Process create(String[] command) throws Exception;
  }
}

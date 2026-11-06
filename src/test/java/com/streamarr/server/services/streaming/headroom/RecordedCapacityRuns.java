package com.streamarr.server.services.streaming.headroom;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.headroom.CpuLimit;
import com.streamarr.server.domain.streaming.headroom.DecodeBackend;
import com.streamarr.server.domain.streaming.headroom.EncoderAndPreset;
import com.streamarr.server.domain.streaming.headroom.JobAttemptLoad;
import com.streamarr.server.domain.streaming.headroom.PixelRate;
import com.streamarr.server.domain.streaming.headroom.SourceDecoder;
import com.streamarr.server.domain.streaming.headroom.ToneMapping;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The libx264 capacity runs behind ADR 0044's evidence: each run encoded several variants at once
 * on a 10-core host, and each row holds one variant's production time for a 6 s segment.
 */
final class RecordedCapacityRuns {

  static final CpuLimit CPU_LIMIT = new CpuLimit(10);

  private static final String RESOURCE = "/streaming-headroom/libx264-capacity-runs.csv";
  private static final double FRAMES_PER_SECOND = 24_000.0 / 1_001;
  private static final PixelRate SOURCE = PixelRate.of(1920, 1080, FRAMES_PER_SECOND);
  private static final SourceDecoder SOFTWARE_H264 =
      new SourceDecoder("h264", DecodeBackend.SOFTWARE);
  private static final EncoderBackend LIBX264 =
      EncoderBackend.builder()
          .codecFamily("h264")
          .encoder("libx264")
          .kind(EncoderBackend.Kind.SOFTWARE)
          .build();

  // Every run kept at least two variants busy on 10 cores, so no ceiling below the CPU limit
  // showed; each preset's ceiling is the whole CPU limit.
  static final Map<EncoderAndPreset, Double> CORES_ALONE =
      Arrays.stream(EncoderPreset.values())
          .collect(
              Collectors.toMap(
                  preset -> new EncoderAndPreset(LIBX264, preset), _ -> CPU_LIMIT.cores()));

  /** One variant of one run. */
  record RecordedVariant(JobAttemptLoad load, Duration segment, Duration productionTime) {

    double productionSpeed() {
      return (double) segment.toNanos() / productionTime.toNanos();
    }
  }

  /** The variants that encoded at once in one run. */
  record RecordedRun(String name, List<RecordedVariant> variants) {

    List<JobAttemptLoad> loadsBeside(RecordedVariant variant) {
      return variants.stream()
          .filter(other -> other != variant)
          .map(RecordedVariant::load)
          .toList();
    }
  }

  private RecordedCapacityRuns() {}

  static List<RecordedRun> load() {
    var runs = new LinkedHashMap<String, List<RecordedVariant>>();
    readRows()
        .forEach(
            row -> {
              var columns = row.split(",");
              runs.computeIfAbsent(columns[0], _ -> new ArrayList<>()).add(variant(columns));
            });
    return runs.entrySet().stream()
        .map(run -> new RecordedRun(run.getKey(), List.copyOf(run.getValue())))
        .toList();
  }

  private static RecordedVariant variant(String[] columns) {
    var preset = EncoderPreset.valueOf(columns[1].toUpperCase(Locale.ROOT));
    var output =
        PixelRate.of(Integer.parseInt(columns[2]), Integer.parseInt(columns[3]), FRAMES_PER_SECOND);
    var load =
        JobAttemptLoad.builder()
            .encoderAndPreset(new EncoderAndPreset(LIBX264, preset))
            .outputPixelRate(output)
            .sourceDecoder(SOFTWARE_H264)
            .sourcePixelRate(SOURCE)
            .toneMapping(ToneMapping.NONE)
            .build();
    return new RecordedVariant(
        load,
        Duration.ofSeconds(Long.parseLong(columns[4])),
        Duration.ofNanos(Math.round(Double.parseDouble(columns[5]) * 1_000_000_000)));
  }

  private static List<String> readRows() {
    try (InputStream stream = RecordedCapacityRuns.class.getResourceAsStream(RESOURCE)) {
      assertThat(stream).as(RESOURCE).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .filter(line -> !line.startsWith("#") && !line.startsWith("run,") && !line.isBlank())
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

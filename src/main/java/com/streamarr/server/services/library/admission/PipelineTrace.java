package com.streamarr.server.services.library.admission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-stage CSV trace of the pipeline strategy, written next to the shared sampler file ({@code
 * poc.sampler.file} with {@code -pipeline} before the extension) while a pipeline run is active.
 * Off when {@code poc.sampler.file} is unset.
 */
@Slf4j
final class PipelineTrace {

  static final String HEADER =
      "epoch_ms,workload,walked,registered,short_circuited,identify_forked,identify_active,"
          + "identify_in_transit,blocked_handoffs,persist_forked,persist_active,calls_per_item,"
          + "identify_wait_ms,persist_wait_ms,tmdb_backlog_ms,hikari_awaiting,inflight,"
          + "register_active,register_blocked";

  private final Path file;
  private final Duration period;
  private final Object lock = new Object();

  PipelineTrace(String samplerFile, Duration period) {
    this.file = traceFile(samplerFile).orElse(null);
    this.period = period;
  }

  private static Optional<Path> traceFile(String samplerFile) {
    if (samplerFile == null || samplerFile.isBlank()) {
      return Optional.empty();
    }

    var name = samplerFile.endsWith(".csv")
        ? samplerFile.substring(0, samplerFile.length() - 4) + "-pipeline.csv"
        : samplerFile + "-pipeline.csv";
    return Optional.of(Path.of(name));
  }

  /** Starts sampling {@code row} every period; returns null when the trace is off. */
  Thread start(String label, Supplier<String> row) {
    if (file == null) {
      return null;
    }

    return Thread.ofVirtual()
        .name("poc-pipeline-trace-" + label)
        .start(
            () -> {
              try {
                while (!Thread.currentThread().isInterrupted()) {
                  append(row.get());
                  Thread.sleep(period);
                }
              } catch (InterruptedException _) {
                // stop() interrupts: write the final row below.
              }

              append(row.get());
            });
  }

  void stop(Thread sampler) {
    if (sampler == null) {
      return;
    }

    sampler.interrupt();
    try {
      sampler.join(Duration.ofSeconds(2));
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  static String decimal(double value) {
    return Double.isNaN(value) ? "NaN" : String.format(Locale.ROOT, "%.3f", value);
  }

  private void append(String line) {
    synchronized (lock) {
      try {
        var fresh = !Files.exists(file) || Files.size(file) == 0;
        try (var writer =
            Files.newBufferedWriter(
                file,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
          if (fresh) {
            writer.write(HEADER);
            writer.write('\n');
          }

          writer.write(line);
          writer.write('\n');
        }
      } catch (IOException e) {
        log.warn("POC pipeline trace row failed", e);
      }
    }
  }
}

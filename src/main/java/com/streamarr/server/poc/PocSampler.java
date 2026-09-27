package com.streamarr.server.poc;

import com.streamarr.server.config.http.PacedTmdbGate;
import com.streamarr.server.services.library.admission.AdmissionRuntime;
import com.streamarr.server.services.library.admission.TaskOutcome;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Appends one CSV row every {@code poc.sampler.period} (default 250 ms) to {@code
 * poc.sampler.file}, flushing each row. Off when the file is unset. Columns use the poc meter names
 * with dots replaced by underscores.
 */
@Slf4j
@Component
public class PocSampler implements SmartLifecycle {

  static final String HEADER =
      "epoch_ms,phase,poc_scan_inflight,worked,short_circuited,failed,poc_admission_limit,"
          + "poc_tmdb_backlog,tmdb_api,tmdb_image,tmdb_retries,poc_hikari_active,"
          + "poc_hikari_awaiting,heap_used_mb,platform_threads";

  private static volatile String phase = "";

  private final AdmissionRuntime runtime;
  private final PacedTmdbGate gate;
  private final HikariLive hikari;
  private final String file;
  private final Duration period;
  private ScheduledExecutorService scheduler;
  private final Object writeLock = new Object();
  private Writer writer;
  private volatile boolean running;

  public PocSampler(
      AdmissionRuntime runtime,
      PacedTmdbGate gate,
      HikariLive hikari,
      @Value("${poc.sampler.file:}") String file,
      @Value("${poc.sampler.period:250ms}") Duration period) {
    this.runtime = runtime;
    this.gate = gate;
    this.hikari = hikari;
    this.file = file;
    this.period = period;
  }

  /** Sets the phase column of later rows; blank until something calls this. */
  public static void setPhase(String newPhase) {
    phase = newPhase == null ? "" : newPhase.replaceAll("[,\\r\\n]", " ");
  }

  @Override
  public void start() {
    running = true;
    if (file == null || file.isBlank()) {
      return;
    }

    try {
      var path = Path.of(file);
      var fresh = !Files.exists(path) || Files.size(path) == 0;
      writer =
          Files.newBufferedWriter(
              path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      if (fresh) {
        writeLine(HEADER);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot open poc sampler file " + file, e);
    }

    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              var thread = new Thread(runnable, "poc-sampler");
              thread.setDaemon(true);
              return thread;
            });
    scheduler.scheduleAtFixedRate(
        this::sampleSafely, 0, period.toNanos(), TimeUnit.NANOSECONDS);
    log.info("POC sampler writing {} every {}", file, period);
  }

  @Override
  public void stop() {
    running = false;
    if (scheduler == null) {
      return;
    }

    scheduler.shutdownNow();
    try {
      scheduler.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }

    sampleSafely();
    try {
      synchronized (writeLock) {
        writer.close();
      }
    } catch (IOException e) {
      log.warn("Closing poc sampler file failed", e);
    }

    scheduler = null;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void sampleSafely() {
    try {
      writeLine(row());
    } catch (RuntimeException | IOException e) {
      log.warn("POC sampler row failed", e);
    }
  }

  private String row() {
    var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    return String.join(
        ",",
        Long.toString(System.currentTimeMillis()),
        phase,
        Integer.toString(runtime.inFlight()),
        Long.toString(runtime.completed(TaskOutcome.WORKED)),
        Long.toString(runtime.completed(TaskOutcome.SHORT_CIRCUITED)),
        Long.toString(runtime.failedCount()),
        decimal(runtime.admissionLimit()),
        decimal(gate.backlogNanos() / 1e6),
        Long.toString(gate.apiRequests()),
        Long.toString(gate.imageRequests()),
        Long.toString(gate.retries()),
        Integer.toString(hikari.active()),
        Integer.toString(hikari.awaiting()),
        decimal(memory.getUsed() / (1024.0 * 1024.0)),
        Integer.toString(ManagementFactory.getThreadMXBean().getThreadCount()));
  }

  private static String decimal(double value) {
    return Double.isNaN(value) ? "NaN" : String.format(Locale.ROOT, "%.3f", value);
  }

  private void writeLine(String line) throws IOException {
    synchronized (writeLock) {
      writer.write(line);
      writer.write('\n');
      writer.flush();
    }
  }
}

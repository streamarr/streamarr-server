package com.streamarr.server.services.library;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.task.ProbeClaim;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.task.FileProcessingTaskCoordinator;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

@Builder
@Slf4j
public class ProbeTaskDispatcher implements AutoCloseable {

  @NonNull private final FileProcessingTaskCoordinator coordinator;
  @NonNull private final FfprobeService producer;
  @NonNull private final FileSystem fileSystem;
  @NonNull private final FileStabilityChecker stabilityChecker;
  @NonNull private final PersistedProbeReader reader;
  @Builder.Default private final int maxConcurrent = 2;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final AtomicInteger occupied = new AtomicInteger();
  private final ConcurrentHashMap<UUID, RunningProbe> running = new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  @Scheduled(fixedDelayString = "${task.probe.poll-interval-ms:1000}")
  public synchronized void dispatch() {
    if (closed.get()) {
      return;
    }

    for (int attempt = 0; attempt < maxConcurrent; attempt++) {
      if (occupied.get() >= maxConcurrent) {
        return;
      }

      var claim = coordinator.claimProbeTask();
      if (claim.isEmpty()) {
        return;
      }

      occupied.incrementAndGet();
      executor.submit(() -> execute(claim.orElseThrow()));
    }
  }

  private void execute(ProbeClaim claim) {
    var execution = new RunningProbe(claim, Thread.currentThread());
    running.put(claim.claimId(), execution);
    try {
      if (!isActive(claim)) {
        return;
      }

      probe(claim);
    } catch (Exception exception) {
      if (!isActive(claim)) {
        return;
      }

      log.warn("Probe execution failed for task {}", claim.taskId(), exception);
      coordinator.retryProbe(claim, exception.toString());
    } finally {
      running.remove(claim.claimId(), execution);
      occupied.decrementAndGet();
    }
  }

  private void probe(ProbeClaim claim) throws IOException {
    var request = claim.request();
    if (request.probeVersion() > ProbeVersion.CURRENT) {
      coordinator.failProbe(claim, "Unsupported probe version: " + request.probeVersion());
      return;
    }

    var path = FilepathCodec.decode(fileSystem, request.filepathUri());
    var stable = stabilityChecker.waitForStability(path);
    if (!isActive(claim)) {
      return;
    }

    if (!stable) {
      coordinator.retryProbe(claim, "Source did not stabilize");
      return;
    }

    var before = snapshot(path);
    if (!before.equals(request.snapshot()) || request.probeVersion() < ProbeVersion.CURRENT) {
      coordinator.rescheduleProbe(
          claim, request.toBuilder().snapshot(before).probeVersion(ProbeVersion.CURRENT).build());
      return;
    }

    if (reader
        .find(request.mediaFileId())
        .filter(stored -> stored.matches(before, request.probeVersion()))
        .isPresent()) {
      coordinator.completeProbe(claim);
      return;
    }

    var outcome = producer.probe(path);
    if (!isActive(claim)) {
      return;
    }

    var after = snapshot(path);
    if (!after.equals(before)) {
      coordinator.rescheduleProbe(claim, request.toBuilder().snapshot(after).build());
      return;
    }

    coordinator.publishProbe(
        ProbePublication.builder()
            .claim(claim)
            .snapshot(request.snapshot())
            .probeVersion(ProbeVersion.CURRENT)
            .outcome(outcome)
            .build());
  }

  @Scheduled(fixedDelayString = "${task.coordinator.heartbeat-interval-ms:15000}")
  public void heartbeat() {
    if (closed.get()) {
      return;
    }

    running.values().forEach(this::renew);
  }

  private void renew(RunningProbe execution) {
    try {
      if (coordinator.renewProbe(execution.claim())) {
        return;
      }
    } catch (Exception exception) {
      log.warn("Could not renew probe claim {}", execution.claim().claimId(), exception);
    }

    running.remove(execution.claim().claimId(), execution);
    execution.thread().interrupt();
  }

  private boolean isActive(ProbeClaim claim) {
    return !closed.get()
        && !Thread.currentThread().isInterrupted()
        && running.containsKey(claim.claimId());
  }

  private static SourceFileSnapshot snapshot(Path path) throws IOException {
    var attributes = Files.readAttributes(path, BasicFileAttributes.class);
    return new SourceFileSnapshot(attributes.size(), attributes.lastModifiedTime().toInstant());
  }

  @Override
  @PreDestroy
  public synchronized void close() {
    closed.set(true);
    running.clear();
    executor.shutdownNow();
  }

  private record RunningProbe(ProbeClaim claim, Thread thread) {}
}

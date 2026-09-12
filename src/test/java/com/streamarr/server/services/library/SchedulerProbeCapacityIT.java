package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.ScheduledExecution;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerConfigurationSupport;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.stats.StatsRegistry;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.task.ProbeRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.probe.ProbeRequests;
import com.streamarr.server.services.streaming.FfprobeService;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

@Tag("IntegrationTest")
@DisplayName("Configured probe concurrency")
class SchedulerProbeCapacityIT extends AbstractIntegrationTest {

  @TempDir Path tempDir;

  @Autowired private ProbeRequests scheduling;
  @Autowired private DataSource dataSource;
  @Autowired private MediaFileContainerInfoRepository outcomes;
  @Autowired private PersistedProbeReader reader;
  @Autowired private MediaFileRepository mediaFiles;
  @Autowired private LibraryRepository libraries;
  @Autowired private DSLContext dsl;
  @Autowired private ProbeTaskCompletion probeTaskCompletion;
  @Autowired private DbSchedulerCustomizer schedulerCustomizer;
  @Autowired private Environment environment;

  private final BlockedProducer producer = new BlockedProducer();
  private final List<MediaFile> createdFiles = new ArrayList<>();
  private UUID libraryId;
  private Scheduler scheduler;

  @BeforeEach
  void setUp() {
    dsl.deleteFrom(DSL.table("scheduled_tasks")).execute();
  }

  @AfterEach
  void tearDown() {
    producer.release();
    if (scheduler != null) {
      scheduler.stop();
    }

    dsl.deleteFrom(DSL.table("scheduled_tasks")).execute();
    mediaFiles.deleteAll(createdFiles);
    if (libraryId != null) {
      libraries.deleteById(libraryId);
    }
  }

  @Test
  @DisplayName(
      "Should limit concurrent probes when more requests are due than the configured capacity")
  void shouldLimitConcurrentProbesWhenMoreRequestsAreDueThanTheConfiguredCapacity()
      throws Exception {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    libraryId = library.getId();
    var requests = new ArrayList<ProbeRequest>();
    for (var index = 0; index < 6; index++) {
      var request = request(createMediaFile());
      requests.add(request);
      scheduling.request(request);
    }

    var firstBatchSubmitted = new CompletableFuture<Void>();
    var allExecutionsFinished = new CountDownLatch(requests.size());
    var listener =
        new AbstractSchedulerListener() {
          @Override
          public void onSchedulerEvent(SchedulerEventType type) {
            if (type == SchedulerEventType.RAN_EXECUTE_DUE) {
              firstBatchSubmitted.complete(null);
            }
          }

          @Override
          public void onExecutionComplete(ExecutionComplete executionComplete) {
            allExecutionsFinished.countDown();
          }
        };
    var execution =
        ProbeExecution.builder()
            .mediaFiles(mediaFiles)
            .reader(reader)
            .producer(producer)
            .stabilityChecker(_ -> true)
            .fileSystem(FileSystems.getDefault())
            .outcomes(outcomes)
            .build();
    var task = MediaProbeTask.create(execution, probeTaskCompletion);
    var properties =
        Binder.get(environment)
            .bind("db-scheduler", Bindable.of(DbSchedulerProperties.class))
            .get();
    properties.setThreads(2);
    scheduler =
        DbSchedulerConfigurationSupport.buildScheduler(
            properties,
            schedulerCustomizer,
            StatsRegistry.NOOP,
            Instant::now,
            dataSource,
            List.of(task),
            List.of(listener),
            List.of());
    var client =
        SchedulerClient.Builder.create(dataSource, task)
            .serializer(schedulerCustomizer.serializer().orElseThrow())
            .build();
    scheduler.start();

    try {
      firstBatchSubmitted.get(15, TimeUnit.SECONDS);
      var firstBatch =
          requests.stream()
              .filter(
                  request ->
                      client
                          .getScheduledExecution(instanceOf(request))
                          .filter(ScheduledExecution::isPicked)
                          .isPresent())
              .toList();
      assertThat(firstBatch).isNotEmpty();
      for (var request : firstBatch) {
        producer.awaitStarted(FilepathCodec.decode(request.filepathUri()));
      }
    } finally {
      producer.release();
    }

    assertThat(allExecutionsFinished.await(15, TimeUnit.SECONDS)).isTrue();
    for (var request : requests) {
      assertThat(reader.find(request.mediaFileId()))
          .hasValueSatisfying(
              stored -> {
                assertThat(stored.snapshot()).isEqualTo(request.snapshot());
                assertThat(stored.probeVersion()).isEqualTo(request.probeVersion());
              });
      assertThat(client.getScheduledExecution(instanceOf(request))).isEmpty();
    }

    assertThat(producer.peakConcurrency())
        .as("Configured capacity of 2 must limit active probe producers")
        .isLessThanOrEqualTo(2);
  }

  private MediaFile createMediaFile() throws IOException {
    var source = Files.createTempFile(tempDir, "probe", ".mkv");
    Files.write(source, new byte[] {1, 2, 3});
    var file =
        mediaFiles.saveAndFlush(
            MediaFile.builder()
                .libraryId(libraryId)
                .status(MediaFileStatus.MATCHED)
                .filename(source.getFileName().toString())
                .filepathUri(FilepathCodec.encode(source))
                .size(3)
                .build());
    createdFiles.add(file);
    return file;
  }

  private static ProbeRequest request(MediaFile file) throws IOException {
    var path = FilepathCodec.decode(file.getFilepathUri());
    var attributes = Files.readAttributes(path, BasicFileAttributes.class);
    return ProbeRequest.builder()
        .mediaFileId(file.getId())
        .libraryId(file.getLibraryId())
        .filepathUri(file.getFilepathUri())
        .snapshot(
            new SourceFileSnapshot(attributes.size(), attributes.lastModifiedTime().toInstant()))
        .probeVersion(ProbeVersion.CURRENT)
        .build();
  }

  private static TaskInstanceId instanceOf(ProbeRequest request) {
    return TaskInstanceId.of(MediaProbeTask.NAME, request.mediaFileId().toString());
  }

  private static final class BlockedProducer implements FfprobeService {

    private final FakeFfprobeService delegate = new FakeFfprobeService();
    private final CountDownLatch release = new CountDownLatch(1);
    private final ConcurrentHashMap<Path, CompletableFuture<Void>> started =
        new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();

    @Override
    public ProbeOutcome probe(Path path) {
      peak.accumulateAndGet(active.incrementAndGet(), Math::max);
      started.computeIfAbsent(path, _ -> new CompletableFuture<>()).complete(null);
      try {
        release.await();
        return delegate.probe(path);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new ProbeExecutionException(exception);
      } finally {
        active.decrementAndGet();
      }
    }

    private void awaitStarted(Path path) throws Exception {
      started.computeIfAbsent(path, _ -> new CompletableFuture<>()).get(15, TimeUnit.SECONDS);
    }

    private void release() {
      release.countDown();
    }

    private int peakConcurrency() {
      return peak.get();
    }
  }
}

package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.AudioStreamProperties;
import com.streamarr.server.domain.streaming.ChapterKnowledge;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.LoopbackProbeWorker;
import com.streamarr.server.fixtures.WorkerProbeResultFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.support.BoundedTask;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Probe Version 7 Audio Facts Integration Tests")
class ProbeVersion7AudioFactsIT extends AbstractIntegrationTest {

  private static final int VERSION_7 = ProbeVersion.FIRST_TO_REPORT_AUDIO_FACTS;

  @TempDir Path directory;

  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private MediaFileContainerInfoRepository containerInfoRepository;

  @Test
  @DisplayName(
      "Should read each audio stream's layout, format, depth, dispositions and FLAC block size back"
          + " when a worker reports them in probe version 7")
  void shouldReadEachAudioStreamsLayoutFormatDepthDispositionsAndFlacBlockSizeBackWhenReported()
      throws Exception {
    var media = WorkerProbeResultFixture.version4MediaBuilder();
    media.addStreams(
        ProbeStreamInfo.newBuilder()
            .setIndex(1)
            .setCodecType("audio")
            .setCodec("flac")
            .setChannels(4)
            .setChannelLayout("quad")
            .setSampleFormat("s32")
            .setBitDepth(24)
            .setIsComment(false)
            .setIsVisualImpaired(true)
            .setIsDescriptions(true)
            .setIsOriginal(true)
            .setIsDub(false)
            .setIsCleanEffects(false)
            .setIsDependent(false)
            .setFlacMinimumBlockSizeSamples(4_608));
    // PCM in Matroska states only a channel count, so ffprobe names no layout.
    media.addStreams(
        ProbeStreamInfo.newBuilder()
            .setIndex(2)
            .setCodecType("audio")
            .setCodec("pcm_s16le")
            .setChannels(2)
            .setSampleFormat("s16")
            .setBitDepth(16)
            .setIsComment(true)
            .setIsVisualImpaired(false)
            .setIsDescriptions(false)
            .setIsOriginal(false)
            .setIsDub(true)
            .setIsCleanEffects(false)
            .setIsDependent(false));
    media.addStreams(
        ProbeStreamInfo.newBuilder().setIndex(3).setCodecType("subtitle").setIsComment(true));
    var file = createMediaFile();

    publish(file, probeWithWorker(media));

    var stored =
        containerInfoRepository
            .findByMediaFileId(file.getId())
            .orElseThrow()
            .getOutcome(KeyframeKnowledge.NotReported::new, ChapterKnowledge.NotReported::new);
    assertThat(stored)
        .isInstanceOfSatisfying(
            ProbeOutcome.Success.class,
            success -> {
              var flac = success.streams().get(1);
              assertThat(flac.bitDepth()).hasValue(24);
              assertThat(flac.comment()).contains(false);
              assertThat(flac.audioProperties())
                  .contains(
                      AudioStreamProperties.builder()
                          .channelLayout(Optional.of("quad"))
                          .sampleFormat(Optional.of("s32"))
                          .visualImpaired(true)
                          .descriptions(true)
                          .original(true)
                          .flacMinimumBlockSizeSamples(OptionalInt.of(4_608))
                          .build());
              var pcm = success.streams().get(2);
              assertThat(pcm.bitDepth()).hasValue(16);
              assertThat(pcm.comment()).contains(true);
              assertThat(pcm.audioProperties())
                  .contains(
                      AudioStreamProperties.builder()
                          .sampleFormat(Optional.of("s16"))
                          .dub(true)
                          .build());
              var subtitle = success.streams().get(3);
              assertThat(subtitle.comment()).contains(true);
              assertThat(subtitle.audioProperties()).isEmpty();
              assertThat(success.streams().getFirst())
                  .extracting(StreamInfo::comment, StreamInfo::audioProperties)
                  .containsExactly(Optional.empty(), Optional.empty());
            });
  }

  private ProbeOutcome.Success probeWithWorker(ProbeMediaInfo.Builder media) throws Exception {
    try (var server =
        WorkerSessionServer.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(serverConfigurationBuilder().build())
            .segmentStore(new FakeSegmentStore())
            .meterRegistry(new SimpleMeterRegistry())
            .build()) {
      server.start();
      try (var worker =
          LoopbackProbeWorker.builder()
              .port(server.port())
              .probeVersion(VERSION_7)
              .availableSlots(1)
              .backgroundProbeCapacity(1)
              .build()) {
        var service = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, directory);
        var request =
            ProbeExecutionRequest.builder()
                .sourcePath(directory.resolve("movie.mkv"))
                .attemptId(UUID.randomUUID())
                .probeVersion(VERSION_7)
                .priority(ProbePriority.BACKGROUND)
                .build();
        try (var result = BoundedTask.start(() -> service.probe(request))) {
          var dispatched = worker.nextResponse().getStartProbe().getRequest();
          worker.reply(
              ProbeAttemptResult.newBuilder()
                  .setProbeAttemptId(dispatched.getProbeAttemptId())
                  .setProbeVersion(dispatched.getProbeVersion())
                  .setMedia(media)
                  .build());
          var outcome = result.await(Duration.ofSeconds(5));
          assertThat(outcome).isInstanceOf(ProbeOutcome.Success.class);
          return (ProbeOutcome.Success) outcome;
        }
      }
    }
  }

  private MediaFile createMediaFile() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie =
        movieRepository.saveAndFlush(Movie.builder().title("Audio facts").library(library).build());
    var source = directory.resolve("movie.mkv");
    return mediaFileRepository.saveAndFlush(
        MediaFile.builder()
            .mediaId(movie.getId())
            .libraryId(library.getId())
            .status(MediaFileStatus.MATCHED)
            .filename(source.getFileName().toString())
            .filepathUri(FilepathCodec.encode(source))
            .build());
  }

  private void publish(MediaFile file, ProbeOutcome.Success outcome) {
    assertThat(
            containerInfoRepository.publish(
                ProbePublication.builder()
                    .mediaFileId(file.getId())
                    .snapshot(new SourceFileSnapshot(4096, Instant.parse("2026-11-02T10:00:00Z")))
                    .probeVersion(VERSION_7)
                    .outcome(outcome)
                    .build()))
        .isTrue();
  }
}

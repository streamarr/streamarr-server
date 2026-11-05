package com.streamarr.server.repositories.playbackreport;

import static com.streamarr.server.jooq.generated.tables.PlaybackReport.PLAYBACK_REPORT;
import static com.streamarr.server.jooq.generated.tables.PlaybackReportDecision.PLAYBACK_REPORT_DECISION;
import static com.streamarr.server.jooq.generated.tables.Profile.PROFILE;
import static org.jooq.impl.DSL.coalesce;

import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoRange;
import com.streamarr.server.jooq.generated.tables.records.PlaybackReportDecisionRecord;
import com.streamarr.server.jooq.generated.tables.records.PlaybackReportRecord;
import com.streamarr.server.repositories.Keyset;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.playbackreport.NewPlaybackReport;
import com.streamarr.server.services.playbackreport.PlaybackReport;
import com.streamarr.server.services.playbackreport.PlaybackReportSubmission;
import com.streamarr.server.services.playbackreport.PlayerFigures;
import com.streamarr.server.services.playbackreport.PlayerFigures.PictureSize;
import com.streamarr.server.services.playbackreport.PlayerFigures.PlayerAudio;
import com.streamarr.server.services.playbackreport.PlayerFigures.PlayerAudioOutput;
import com.streamarr.server.services.playbackreport.PlayerFigures.PlayerBrowser;
import com.streamarr.server.services.playbackreport.PlayerFigures.PlayerDisplay;
import com.streamarr.server.services.playbackreport.PlayerFigures.PlayerScreen;
import com.streamarr.server.services.playbackreport.PlayerFigures.QualityStep;
import com.streamarr.server.services.playbackreport.PlayerHdrMode;
import com.streamarr.server.services.streaming.AudioFormat;
import com.streamarr.server.services.streaming.PlaybackDecision;
import com.streamarr.server.services.streaming.PlaybackDecision.TrackDelivery;
import com.streamarr.server.services.streaming.PlaybackDelivery;
import com.streamarr.server.services.streaming.VideoFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.SelectOnConditionStep;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
@RequiredArgsConstructor
public class PlaybackReportRepositoryImpl implements PlaybackReportRepository {

  private static final Keyset<OffsetDateTime> NEWEST_FIRST =
      Keyset.<OffsetDateTime>builder()
          .sortValue(PLAYBACK_REPORT.SHARED_AT)
          .itemId(PLAYBACK_REPORT.ID)
          .build();

  private static final PlayerDisplay UNKNOWN_DISPLAY =
      PlayerDisplay.builder()
          .width(OptionalInt.empty())
          .height(OptionalInt.empty())
          .hdrMode(Optional.empty())
          .refreshRate(OptionalDouble.empty())
          .dynamicRangeMatched(Optional.empty())
          .frameRateMatched(Optional.empty())
          .build();

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;

  @Override
  public UUID save(NewPlaybackReport report) {
    var row = dsl.newRecord(PLAYBACK_REPORT);
    row.setSharedAt(utc(report.sharedAt()));
    row.setProfileId(report.profileId());
    row.setPlaybackSessionId(report.playbackSessionId());
    row.setStreamSessionId(report.streamSessionId());
    row.setSubmissionId(report.submissionId());
    row.setMediaFileId(report.mediaFileId());
    row.setNote(report.note().orElse(null));
    row.setRequestedChoice(report.requestedChoice().name());
    row.setPlaybackChoice(report.playbackChoice().name());
    row.setCapabilityDeclaration(
        JSONB.jsonb(objectMapper.writeValueAsString(report.capabilityDeclaration())));
    setPlayerFigures(row, report.player());
    var inserted =
        dsl.insertInto(PLAYBACK_REPORT)
            .set(row)
            .onConflict(PLAYBACK_REPORT.PLAYBACK_SESSION_ID, PLAYBACK_REPORT.SUBMISSION_ID)
            .doNothing()
            .returning(PLAYBACK_REPORT.ID)
            .fetchOptional(PLAYBACK_REPORT.ID);
    if (inserted.isEmpty()) {
      return storedIdOf(report);
    }

    var id = inserted.get();
    var decisions = report.decisions().stream().map(decision -> decisionRow(id, decision)).toList();
    if (!decisions.isEmpty()) {
      dsl.batchInsert(decisions).execute();
    }

    return id;
  }

  @Override
  public Optional<UUID> findIdBySubmission(PlaybackReportSubmission submission) {
    return dsl.select(PLAYBACK_REPORT.ID)
        .from(PLAYBACK_REPORT)
        .where(PLAYBACK_REPORT.PLAYBACK_SESSION_ID.eq(submission.playbackSessionId()))
        .and(PLAYBACK_REPORT.SUBMISSION_ID.eq(submission.submissionId()))
        .and(PLAYBACK_REPORT.PROFILE_ID.eq(submission.profileId()))
        .fetchOptional(PLAYBACK_REPORT.ID);
  }

  @Override
  public List<Row<PlaybackReport>> rowsBeyond(Seek seek) {
    var fetched = NEWEST_FIRST.rowsBeyond(selectReports(), seek).fetch();
    var decisions = decisionsOf(fetched.getValues(PLAYBACK_REPORT.ID));
    return fetched.stream()
        .map(row -> new Row<>(report(row, decisions), NEWEST_FIRST.rowOf(row).position()))
        .toList();
  }

  @Override
  public Optional<PlaybackReport> findById(UUID id) {
    return selectReports()
        .where(PLAYBACK_REPORT.ID.eq(id))
        .fetchOptional()
        .map(row -> report(row, decisionsOf(List.of(id))));
  }

  @Override
  public int countUnread() {
    return dsl.fetchCount(PLAYBACK_REPORT, PLAYBACK_REPORT.READ_AT.isNull());
  }

  @Override
  public Optional<PlaybackReport> markRead(UUID id, Instant readAt) {
    var updated =
        dsl.update(PLAYBACK_REPORT)
            .set(PLAYBACK_REPORT.READ_AT, coalesce(PLAYBACK_REPORT.READ_AT, utc(readAt)))
            .where(PLAYBACK_REPORT.ID.eq(id))
            .execute();
    if (updated == 0) {
      return Optional.empty();
    }

    return findById(id);
  }

  @Override
  public int deleteSharedBefore(Instant cutoff) {
    return dsl.deleteFrom(PLAYBACK_REPORT)
        .where(PLAYBACK_REPORT.SHARED_AT.lt(utc(cutoff)))
        .execute();
  }

  // ON CONFLICT DO NOTHING waits for a concurrent insert of the same submission to commit, so this
  // read-committed statement sees that report.
  private UUID storedIdOf(NewPlaybackReport report) {
    return dsl.select(PLAYBACK_REPORT.ID)
        .from(PLAYBACK_REPORT)
        .where(PLAYBACK_REPORT.PLAYBACK_SESSION_ID.eq(report.playbackSessionId()))
        .and(PLAYBACK_REPORT.SUBMISSION_ID.eq(report.submissionId()))
        .fetchSingle(PLAYBACK_REPORT.ID);
  }

  private SelectOnConditionStep<Record> selectReports() {
    return dsl.select(PLAYBACK_REPORT.asterisk(), PROFILE.NAME)
        .from(PLAYBACK_REPORT)
        .join(PROFILE)
        .on(PROFILE.ID.eq(PLAYBACK_REPORT.PROFILE_ID));
  }

  private Map<UUID, List<PlaybackDecision>> decisionsOf(Collection<UUID> reportIds) {
    return dsl
        .selectFrom(PLAYBACK_REPORT_DECISION)
        .where(PLAYBACK_REPORT_DECISION.PLAYBACK_REPORT_ID.in(reportIds))
        .orderBy(
            PLAYBACK_REPORT_DECISION.PLAYBACK_REPORT_ID, PLAYBACK_REPORT_DECISION.FORMAT_ATTEMPT)
        .fetch()
        .stream()
        .collect(
            Collectors.groupingBy(
                PlaybackReportDecisionRecord::getPlaybackReportId,
                Collectors.mapping(PlaybackReportRepositoryImpl::decision, Collectors.toList())));
  }

  private PlaybackReport report(Record fetched, Map<UUID, List<PlaybackDecision>> decisions) {
    var row = fetched.into(PLAYBACK_REPORT);
    return PlaybackReport.builder()
        .id(row.getId())
        .sharedAt(row.getSharedAt().toInstant())
        .readAt(Optional.ofNullable(row.getReadAt()).map(OffsetDateTime::toInstant))
        .profileId(row.getProfileId())
        .profileName(fetched.get(PROFILE.NAME))
        .playbackSessionId(row.getPlaybackSessionId())
        .streamSessionId(row.getStreamSessionId())
        .mediaFileId(Optional.ofNullable(row.getMediaFileId()))
        .note(Optional.ofNullable(row.getNote()))
        .requestedChoice(VideoQuality.valueOf(row.getRequestedChoice()))
        .playbackChoice(VideoQuality.valueOf(row.getPlaybackChoice()))
        .decisions(decisions.getOrDefault(row.getId(), List.of()))
        .player(playerFigures(row))
        .capabilityDeclaration(
            objectMapper.readValue(
                row.getCapabilityDeclaration().data(), ClientCapabilityDeclaration.class))
        .build();
  }

  private static void setPlayerFigures(PlaybackReportRecord row, PlayerFigures figures) {
    figures
        .qualityStep()
        .ifPresent(
            step -> {
              row.setQualityStepNumber(step.number());
              row.setQualityStepCount(step.count());
              row.setQualityStepHeight(step.height());
              row.setQualityStepBitrate(step.bitrate());
            });
    row.setObservedBitrate(boxed(figures.observedBitrate()));
    row.setBufferSeconds(boxed(figures.bufferSeconds()));
    row.setDroppedFrames(boxed(figures.droppedFrames()));
    row.setStalls(boxed(figures.stalls()));
    figures
        .resolution()
        .ifPresent(
            size -> {
              row.setResolutionWidth(size.width());
              row.setResolutionHeight(size.height());
            });
    row.setVideoCodec(figures.videoCodec().orElse(null));
    row.setHdrMode(figures.hdrMode().map(Enum::name).orElse(null));
    figures
        .audio()
        .ifPresent(
            audio -> {
              row.setAudioCodec(audio.codec());
              row.setAudioChannels(boxed(audio.channels()));
              row.setAudioDolbyAtmos(audio.dolbyAtmos().orElse(null));
            });
    figures.display().ifPresent(display -> setDisplay(row, display));
    figures
        .audioOutput()
        .ifPresent(
            output -> {
              row.setAudioOutputRoute(output.route());
              row.setAudioOutputChannels(output.channels());
            });
    figures
        .screen()
        .ifPresent(
            screen -> {
              row.setScreenWidth(screen.width());
              row.setScreenHeight(screen.height());
              row.setScreenPixelRatio(screen.pixelRatio());
              row.setScreenHdr(screen.hdr());
            });
    figures
        .browser()
        .ifPresent(
            browser -> {
              row.setBrowserName(browser.name());
              row.setBrowserVersion(browser.version());
            });
  }

  private static void setDisplay(PlaybackReportRecord row, PlayerDisplay display) {
    row.setDisplayWidth(boxed(display.width()));
    row.setDisplayHeight(boxed(display.height()));
    row.setDisplayHdrMode(display.hdrMode().map(Enum::name).orElse(null));
    row.setDisplayRefreshRate(boxed(display.refreshRate()));
    row.setDisplayDynamicRangeMatched(display.dynamicRangeMatched().orElse(null));
    row.setDisplayFrameRateMatched(display.frameRateMatched().orElse(null));
  }

  // The migration's checks keep each group's columns null together, apart from the display's
  // figures, which a TV reports one by one while it switches modes.
  private static PlayerFigures playerFigures(PlaybackReportRecord row) {
    return PlayerFigures.builder()
        .qualityStep(
            Optional.ofNullable(row.getQualityStepNumber())
                .map(
                    number ->
                        QualityStep.builder()
                            .number(number)
                            .count(row.getQualityStepCount())
                            .height(row.getQualityStepHeight())
                            .bitrate(row.getQualityStepBitrate())
                            .build()))
        .observedBitrate(optionalDouble(row.getObservedBitrate()))
        .bufferSeconds(optionalDouble(row.getBufferSeconds()))
        .droppedFrames(optionalInt(row.getDroppedFrames()))
        .stalls(optionalInt(row.getStalls()))
        .resolution(
            Optional.ofNullable(row.getResolutionWidth())
                .map(width -> new PictureSize(width, row.getResolutionHeight())))
        .videoCodec(Optional.ofNullable(row.getVideoCodec()))
        .hdrMode(Optional.ofNullable(row.getHdrMode()).map(PlayerHdrMode::valueOf))
        .audio(
            Optional.ofNullable(row.getAudioCodec())
                .map(
                    codec ->
                        PlayerAudio.builder()
                            .codec(codec)
                            .channels(optionalInt(row.getAudioChannels()))
                            .dolbyAtmos(Optional.ofNullable(row.getAudioDolbyAtmos()))
                            .build()))
        .display(display(row))
        .audioOutput(
            Optional.ofNullable(row.getAudioOutputRoute())
                .map(route -> new PlayerAudioOutput(route, row.getAudioOutputChannels())))
        .screen(
            Optional.ofNullable(row.getScreenWidth())
                .map(
                    width ->
                        PlayerScreen.builder()
                            .width(width)
                            .height(row.getScreenHeight())
                            .pixelRatio(row.getScreenPixelRatio())
                            .hdr(row.getScreenHdr())
                            .build()))
        .browser(
            Optional.ofNullable(row.getBrowserName())
                .map(name -> new PlayerBrowser(name, row.getBrowserVersion())))
        .build();
  }

  // A display whose every figure is unknown reads as no display.
  private static Optional<PlayerDisplay> display(PlaybackReportRecord row) {
    var display =
        PlayerDisplay.builder()
            .width(optionalInt(row.getDisplayWidth()))
            .height(optionalInt(row.getDisplayHeight()))
            .hdrMode(Optional.ofNullable(row.getDisplayHdrMode()).map(PlayerHdrMode::valueOf))
            .refreshRate(optionalDouble(row.getDisplayRefreshRate()))
            .dynamicRangeMatched(Optional.ofNullable(row.getDisplayDynamicRangeMatched()))
            .frameRateMatched(Optional.ofNullable(row.getDisplayFrameRateMatched()))
            .build();
    if (display.equals(UNKNOWN_DISPLAY)) {
      return Optional.empty();
    }

    return Optional.of(display);
  }

  private PlaybackReportDecisionRecord decisionRow(UUID reportId, PlaybackDecision decision) {
    var row = dsl.newRecord(PLAYBACK_REPORT_DECISION);
    row.setPlaybackReportId(reportId);
    row.setFormatAttempt(decision.formatAttempt());
    row.setStreamSessionId(decision.streamSessionId());
    var video = decision.video();
    row.setVideoDelivery(video.delivery().name());
    row.setVideoSourceCodec(video.source().codec());
    row.setVideoSourceWidth(video.source().width());
    row.setVideoSourceHeight(video.source().height());
    row.setVideoSourceFrameRate(boxed(video.source().frameRate()));
    row.setVideoSourceBitrate(boxed(video.source().bitrate()));
    row.setVideoSourceRange(video.source().videoRange().name());
    row.setVideoSourceDolbyVision(video.source().dolbyVision());
    row.setVideoOutputCodec(video.output().codec());
    row.setVideoOutputWidth(video.output().width());
    row.setVideoOutputHeight(video.output().height());
    row.setVideoOutputFrameRate(boxed(video.output().frameRate()));
    row.setVideoOutputBitrate(boxed(video.output().bitrate()));
    row.setVideoOutputRange(video.output().videoRange().name());
    row.setVideoOutputDolbyVision(video.output().dolbyVision());
    decision.audio().ifPresent(audio -> setAudio(row, audio));
    row.setReasons(decision.reasons().stream().map(Enum::name).toArray(String[]::new));
    return row;
  }

  private static void setAudio(PlaybackReportDecisionRecord row, TrackDelivery<AudioFormat> audio) {
    row.setAudioDelivery(audio.delivery().name());
    row.setAudioSourceCodec(audio.source().codec());
    row.setAudioSourceChannels(boxed(audio.source().channels()));
    row.setAudioSourceBitrate(boxed(audio.source().bitrate()));
    row.setAudioOutputCodec(audio.output().codec());
    row.setAudioOutputChannels(boxed(audio.output().channels()));
    row.setAudioOutputBitrate(boxed(audio.output().bitrate()));
  }

  private static PlaybackDecision decision(PlaybackReportDecisionRecord row) {
    var video =
        new TrackDelivery<>(
            PlaybackDelivery.valueOf(row.getVideoDelivery()),
            VideoFormat.builder()
                .codec(row.getVideoSourceCodec())
                .width(row.getVideoSourceWidth())
                .height(row.getVideoSourceHeight())
                .frameRate(optionalDouble(row.getVideoSourceFrameRate()))
                .bitrate(optionalLong(row.getVideoSourceBitrate()))
                .videoRange(VideoRange.valueOf(row.getVideoSourceRange()))
                .dolbyVision(row.getVideoSourceDolbyVision())
                .build(),
            VideoFormat.builder()
                .codec(row.getVideoOutputCodec())
                .width(row.getVideoOutputWidth())
                .height(row.getVideoOutputHeight())
                .frameRate(optionalDouble(row.getVideoOutputFrameRate()))
                .bitrate(optionalLong(row.getVideoOutputBitrate()))
                .videoRange(VideoRange.valueOf(row.getVideoOutputRange()))
                .dolbyVision(row.getVideoOutputDolbyVision())
                .build());
    return PlaybackDecision.builder()
        .formatAttempt(row.getFormatAttempt())
        .streamSessionId(row.getStreamSessionId())
        .video(video)
        .audio(Optional.ofNullable(row.getAudioDelivery()).map(delivery -> audio(row, delivery)))
        .reasons(Arrays.stream(row.getReasons()).map(DecisionReason::valueOf).toList())
        .build();
  }

  private static TrackDelivery<AudioFormat> audio(
      PlaybackReportDecisionRecord row, String delivery) {
    return new TrackDelivery<>(
        PlaybackDelivery.valueOf(delivery),
        AudioFormat.builder()
            .codec(row.getAudioSourceCodec())
            .channels(optionalInt(row.getAudioSourceChannels()))
            .bitrate(optionalLong(row.getAudioSourceBitrate()))
            .build(),
        AudioFormat.builder()
            .codec(row.getAudioOutputCodec())
            .channels(optionalInt(row.getAudioOutputChannels()))
            .bitrate(optionalLong(row.getAudioOutputBitrate()))
            .build());
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private static Double boxed(OptionalDouble value) {
    if (value.isEmpty()) {
      return null;
    }

    return value.getAsDouble();
  }

  private static Integer boxed(OptionalInt value) {
    if (value.isEmpty()) {
      return null;
    }

    return value.getAsInt();
  }

  private static Long boxed(OptionalLong value) {
    if (value.isEmpty()) {
      return null;
    }

    return value.getAsLong();
  }

  private static OptionalDouble optionalDouble(Double value) {
    if (value == null) {
      return OptionalDouble.empty();
    }

    return OptionalDouble.of(value);
  }

  private static OptionalInt optionalInt(Integer value) {
    if (value == null) {
      return OptionalInt.empty();
    }

    return OptionalInt.of(value);
  }

  private static OptionalLong optionalLong(Long value) {
    if (value == null) {
      return OptionalLong.empty();
    }

    return OptionalLong.of(value);
  }
}

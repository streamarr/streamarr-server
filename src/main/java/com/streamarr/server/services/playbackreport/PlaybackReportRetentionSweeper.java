package com.streamarr.server.services.playbackreport;

import com.streamarr.server.repositories.playbackreport.PlaybackReportRepository;
import com.streamarr.server.services.audit.SystemWork;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Deletes each shared playback report 30 days after it was shared. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaybackReportRetentionSweeper {

  private static final Duration RETENTION = Duration.ofDays(30);

  private final PlaybackReportRepository playbackReportRepository;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  // An hourly sweep deletes a report at most an hour late. A short initial delay: an instance
  // restarted more often than the delay would never sweep.
  @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
  public void deleteExpiredReports() {
    SystemWork.runAsSystemActor(
        () -> transactionTemplate.executeWithoutResult(_ -> deleteReportsSharedBeforeCutoff()));
  }

  private void deleteReportsSharedBeforeCutoff() {
    var cutoff = clock.instant().minus(RETENTION);
    var deleted = playbackReportRepository.deleteSharedBefore(cutoff);
    if (deleted > 0) {
      log.debug("Deleted {} playback reports shared before {}.", deleted, cutoff);
    }
  }
}

package com.streamarr.server.support;

import com.streamarr.server.domain.notification.NewsUpdate;
import com.streamarr.server.domain.notification.NotificationForm;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.NotificationSnapshot;
import com.streamarr.server.domain.notification.RequestResolution;
import com.streamarr.server.domain.notification.SubjectReference;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * Kinds that ADR 0063 specifies for later builds, defined in test code alone to show that the
 * module's model fits them, and a made-up kind of news about a Household, which no kind built or
 * specified has yet. No domain tells them, so the tests tell them as a domain would, and the
 * module's catalog does not hold them, so the tests read them back as their rows hold them.
 */
public final class SpecifiedOnlyNotificationKinds {

  /** The subject type of the library kinds, which no kind built yet takes. */
  public static final String LIBRARY = "LIBRARY";

  /**
   * Scan or refresh finished: a scan or a refresh of the library finished and changed something. A
   * later one adds its figures to the unread news.
   */
  public static final NotificationKind SCAN_OR_REFRESH_FINISHED =
      NotificationKind.builder()
          .name("LIBRARY_SCAN_OR_REFRESH_FINISHED")
          .form(NotificationForm.NEWS)
          .subjectType(LIBRARY)
          .snapshotType(ScanOrRefreshFinishedSnapshot.class)
          .newsUpdate(new NewsUpdate.AddFigures(ScanOrRefreshFinishedSnapshot.FIGURES))
          .build();

  /** Unmatched items: a finished scan added items that it could not match. */
  public static final NotificationKind UNMATCHED_ITEMS =
      NotificationKind.builder()
          .name("LIBRARY_UNMATCHED_ITEMS")
          .form(NotificationForm.NEWS)
          .subjectType(LIBRARY)
          .snapshotType(UnmatchedItemsSnapshot.class)
          .build();

  /** Made-up news about a Household. */
  public static final NotificationKind HOUSEHOLD_NEWS =
      NotificationKind.builder()
          .name("TEST_HOUSEHOLD_NEWS")
          .form(NotificationForm.NEWS)
          .subjectType(SubjectReference.HOUSEHOLD)
          .snapshotType(HouseholdNewsSnapshot.class)
          .build();

  public static final List<NotificationKind> ALL =
      List.of(SCAN_OR_REFRESH_FINISHED, UNMATCHED_ITEMS, HOUSEHOLD_NEWS);

  private SpecifiedOnlyNotificationKinds() {}

  /** A library's subject reference. */
  public static SubjectReference library(UUID libraryId) {
    return new SubjectReference(LIBRARY, libraryId);
  }

  /**
   * What each ServerAdmin was told: the library's name at the latest scan or refresh, and the
   * figures of every scan and refresh since it last read the news.
   */
  @Builder
  public record ScanOrRefreshFinishedSnapshot(
      @NonNull String libraryName, int titlesAdded, int titlesRemoved, int filesSkipped)
      implements NotificationSnapshot {

    static final Set<String> FIGURES = Set.of("titlesAdded", "titlesRemoved", "filesSkipped");

    @Override
    public String line(Optional<RequestResolution> resolution) {
      return "%s: %d titles added, %d removed, %d files skipped."
          .formatted(libraryName, titlesAdded, titlesRemoved, filesSkipped);
    }
  }

  /** What each ServerAdmin was told: how many unmatched items the library held after the scan. */
  public record UnmatchedItemsSnapshot(@NonNull String libraryName, int unmatchedItems)
      implements NotificationSnapshot {

    @Override
    public String line(Optional<RequestResolution> resolution) {
      return "%s: %d titles need matching.".formatted(libraryName, unmatchedItems);
    }
  }

  /** What a Household's Account was told. */
  public record HouseholdNewsSnapshot(@NonNull String householdName)
      implements NotificationSnapshot {

    @Override
    public String line(Optional<RequestResolution> resolution) {
      return "Something happened in %s.".formatted(householdName);
    }
  }
}

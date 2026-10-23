package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeTaskScheduler;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.services.events.library.LibraryCountsChangedEvent;
import com.streamarr.server.services.events.library.LibraryItemAddedEvent;
import com.streamarr.server.services.events.library.LibraryItemAudience;
import com.streamarr.server.services.events.library.LibraryItemRemovedEvent;
import com.streamarr.server.services.events.library.LibraryItemUpdatedEvent;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Library counts changed publisher")
class LibraryCountsChangedPublisherTest {

  private final CapturingEventPublisher events = new CapturingEventPublisher();
  private final FakeTaskScheduler scheduler = new FakeTaskScheduler();
  private final MutableClock clock = new MutableClock();
  private final LibraryCountsChangedPublisher publisher =
      new LibraryCountsChangedPublisher(events, scheduler, clock);

  @Test
  @DisplayName("Should push the counts at once when the first item change of a library commits")
  void shouldPushTheCountsAtOnceWhenTheFirstItemChangeOfALibraryCommits() {
    var libraryId = UUID.randomUUID();

    publisher.onItemChanged(added(libraryId));
    scheduler.runTasksDueAt(clock.instant());

    assertThat(pushes()).containsExactly(new LibraryCountsChangedEvent(libraryId));
  }

  @Test
  @DisplayName("Should push once more when the interval ends after changes commit within it")
  void shouldPushOnceMoreWhenTheIntervalEndsAfterChangesCommitWithinIt() {
    var libraryId = UUID.randomUUID();
    publisher.onItemChanged(added(libraryId));
    scheduler.runTasksDueAt(clock.instant());

    clock.advance(Duration.ofMillis(500));
    publisher.onItemChanged(added(libraryId));
    publisher.onItemChanged(removed(libraryId));
    publisher.onItemChanged(updatedForEveryProfile(libraryId));
    scheduler.runTasksDueAt(clock.instant());
    var pushesWithinTheInterval = pushes().size();
    clock.advance(Duration.ofMillis(1500));
    scheduler.runTasksDueAt(clock.instant());

    assertThat(pushesWithinTheInterval).isOne();
    assertThat(pushes()).hasSize(2);
    assertThat(scheduler.pendingTaskCount()).isZero();
  }

  @Test
  @DisplayName("Should push at once when an item change commits after a quiet interval")
  void shouldPushAtOnceWhenAnItemChangeCommitsAfterAQuietInterval() {
    var libraryId = UUID.randomUUID();
    publisher.onItemChanged(added(libraryId));
    scheduler.runTasksDueAt(clock.instant());

    clock.advance(Duration.ofSeconds(5));
    publisher.onItemChanged(added(libraryId));
    scheduler.runTasksDueAt(clock.instant());

    assertThat(pushes()).hasSize(2);
  }

  @Test
  @DisplayName("Should push nothing when only the watch state of one Profile changes")
  void shouldPushNothingWhenOnlyTheWatchStateOfOneProfileChanges() {
    var libraryId = UUID.randomUUID();

    publisher.onItemChanged(
        LibraryItemUpdatedEvent.builder()
            .libraryId(libraryId)
            .itemId(UUID.randomUUID())
            .kind(MediaType.MOVIE)
            .audience(LibraryItemAudience.profile(UUID.randomUUID()))
            .build());
    scheduler.runTasksDueAt(clock.instant());

    assertThat(pushes()).isEmpty();
    assertThat(scheduler.pendingTaskCount()).isZero();
  }

  @Test
  @DisplayName("Should push the counts of each library at once when two libraries change together")
  void shouldPushTheCountsOfEachLibraryAtOnceWhenTwoLibrariesChangeTogether() {
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();

    publisher.onItemChanged(added(first));
    publisher.onItemChanged(added(second));
    scheduler.runTasksDueAt(clock.instant());

    assertThat(pushes())
        .containsExactlyInAnyOrder(
            new LibraryCountsChangedEvent(first), new LibraryCountsChangedEvent(second));
  }

  private List<LibraryCountsChangedEvent> pushes() {
    return events.getEventsOfType(LibraryCountsChangedEvent.class);
  }

  private static LibraryItemAddedEvent added(UUID libraryId) {
    return new LibraryItemAddedEvent(libraryId, UUID.randomUUID(), MediaType.MOVIE);
  }

  private static LibraryItemRemovedEvent removed(UUID libraryId) {
    return new LibraryItemRemovedEvent(libraryId, UUID.randomUUID(), MediaType.MOVIE);
  }

  private static LibraryItemUpdatedEvent updatedForEveryProfile(UUID libraryId) {
    return LibraryItemUpdatedEvent.builder()
        .libraryId(libraryId)
        .itemId(UUID.randomUUID())
        .kind(MediaType.MOVIE)
        .audience(LibraryItemAudience.everyProfile())
        .build();
  }
}

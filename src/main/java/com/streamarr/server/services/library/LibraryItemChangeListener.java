package com.streamarr.server.services.library;

import com.streamarr.server.repositories.LibraryItemScopeRepository;
import com.streamarr.server.services.events.library.CollectablesChangedEvent;
import com.streamarr.server.services.events.library.LibraryItemChangedEvent;
import com.streamarr.server.services.watchprogress.events.WatchStateChangedEvent;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class LibraryItemChangeListener {
  private final LibraryItemScopeRepository scopes;
  private final ApplicationEventPublisher events;

  // Resolve parent IDs in the writer's transaction. After-commit delivery never reads on its
  // connection.
  @EventListener
  @Transactional(readOnly = true)
  public void onCollectablesChanged(CollectablesChangedEvent event) {
    publishUpdates(event.itemIds(), Optional.empty());
  }

  @EventListener
  @Transactional(readOnly = true)
  public void onWatchStateChanged(WatchStateChangedEvent event) {
    publishUpdates(event.itemIds(), Optional.of(event.profileId()));
  }

  private void publishUpdates(Collection<UUID> itemIds, Optional<UUID> profileId) {
    scopes
        .findItems(itemIds)
        .forEach(
            item ->
                events.publishEvent(
                    LibraryItemChangedEvent.builder()
                        .change(LibraryItemChangedEvent.Change.UPDATED)
                        .libraryId(item.libraryId())
                        .itemId(item.id())
                        .kind(item.kind())
                        .profileId(profileId)
                        .build()));
  }
}

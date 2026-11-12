package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.NotificationKinds;
import com.streamarr.server.services.events.notification.SharingNotificationKinds;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The kinds the module reads back by their stored names: the sharing kinds, and any kind a bean
 * contributes. A kind keeps its name for good, so a stored Notification always finds its kind.
 */
@Component
public class NotificationKindCatalog implements NotificationKinds {

  private final Map<String, NotificationKind> kinds;

  public NotificationKindCatalog(ObjectProvider<NotificationKind> contributed) {
    kinds =
        Stream.concat(SharingNotificationKinds.ALL.stream(), contributed.orderedStream())
            .collect(Collectors.toUnmodifiableMap(NotificationKind::name, Function.identity()));
  }

  @Override
  public Optional<NotificationKind> kindNamed(String name) {
    return Optional.ofNullable(kinds.get(name));
  }
}

package com.streamarr.server.services.notification.architecturefixture;

import com.streamarr.server.services.events.auth.ProfileShareChangedEvent;
import org.springframework.context.event.EventListener;

/** A Notifications class that hears a share change itself and decides from it whom to tell. */
public class ShareChangeListenerFixture {

  @EventListener
  @SuppressWarnings("java:S1186") // ArchUnit inspects this uninvoked bytecode fixture.
  public void onProfileShareChanged(ProfileShareChangedEvent change) {}
}

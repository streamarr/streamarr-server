package com.streamarr.server.fakes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.ApplicationEventPublisher;

public class CapturingEventPublisher implements ApplicationEventPublisher {

  private final List<Object> publishedEvents = new CopyOnWriteArrayList<>();

  @Override
  public void publishEvent(Object event) {
    publishedEvents.add(event);
  }

  public <T> List<T> getEventsOfType(Class<T> type) {
    return publishedEvents.stream().filter(type::isInstance).map(type::cast).toList();
  }
}

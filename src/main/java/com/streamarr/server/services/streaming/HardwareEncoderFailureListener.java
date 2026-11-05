package com.streamarr.server.services.streaming;

import com.streamarr.server.services.streaming.events.HardwareEncoderFailedEvent;
import com.streamarr.server.services.streaming.events.RequestedVariantHardwareEncoderFailedEvent;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

/**
 * Sends the variant that the player requests to the software encoder of the same codec family when
 * its hardware encoder fails. Any other variant's job attempt has already ended, so that variant
 * stops and the stream session keeps playing (ADR 0044).
 */
@RequiredArgsConstructor
public class HardwareEncoderFailureListener {

  @NonNull private final ProducerLifecycleService producerLifecycle;
  @NonNull private final ApplicationEventPublisher eventPublisher;

  @EventListener
  public void onHardwareEncoderFailed(@NonNull HardwareEncoderFailedEvent event) {
    if (!producerLifecycle.isRequestedVariant(event.streamSessionId(), event.variantLabel())) {
      return;
    }

    eventPublisher.publishEvent(
        RequestedVariantHardwareEncoderFailedEvent.builder()
            .streamSessionId(event.streamSessionId())
            .variantLabel(event.variantLabel())
            .build());
  }
}

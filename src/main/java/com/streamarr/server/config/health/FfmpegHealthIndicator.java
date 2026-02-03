package com.streamarr.server.config.health;

import com.streamarr.server.services.streaming.ffmpeg.TranscodeCapabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FfmpegHealthIndicator implements HealthIndicator {

  private final TranscodeCapabilityService capabilityService;

  @Override
  public Health health() {
    if (!capabilityService.isFfmpegAvailable()) {
      return Health.down().withDetail("reason", "FFmpeg not found").build();
    }

    var hwEncoding = capabilityService.getHardwareEncodingCapability();
    return Health.up()
        .withDetail("gpu", hwEncoding.available())
        .withDetail("encoders", hwEncoding.encoders())
        .withDetail("accelerator", hwEncoding.accelerator())
        .build();
  }
}

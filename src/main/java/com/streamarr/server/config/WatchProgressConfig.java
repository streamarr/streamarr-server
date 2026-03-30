package com.streamarr.server.config;

import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.streaming.WatchProgressRepository;
import com.streamarr.server.services.streaming.StreamSessionRepository;
import com.streamarr.server.services.watchprogress.WatchProgressService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WatchProgressProperties.class)
public class WatchProgressConfig {

  @Bean
  public WatchProgressService watchProgressService(
      StreamSessionRepository sessionRepository,
      WatchProgressRepository watchProgressRepository,
      MediaFileRepository mediaFileRepository,
      EpisodeRepository episodeRepository,
      SeasonRepository seasonRepository,
      WatchProgressProperties properties,
      ApplicationEventPublisher eventPublisher) {
    return new WatchProgressService(
        sessionRepository,
        watchProgressRepository,
        mediaFileRepository,
        episodeRepository,
        seasonRepository,
        properties,
        eventPublisher);
  }
}

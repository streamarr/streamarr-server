package com.streamarr.server.config.persistance;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;
import java.util.UUID;

@Configuration
@EnableJpaAuditing
public class PersistenceConfig {

    // TODO: for testing, replace
    public static class AuditorAwareImpl implements AuditorAware<UUID> {
        @Override
        public Optional<UUID> getCurrentAuditor() {
            return Optional.of(UUID.fromString("cb46514c-04f8-4153-815d-fa044a4bf65e"));
        }
    }

    @Bean
    public AuditorAware<UUID> auditorAware() {
        return new AuditorAwareImpl();
    }

}

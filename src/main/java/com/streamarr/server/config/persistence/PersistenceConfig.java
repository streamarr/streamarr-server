package com.streamarr.server.config.persistence;

import lombok.RequiredArgsConstructor;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.dialect.PostgreSQL10Dialect;
import org.hibernate.reactive.mutiny.Mutiny;
import org.hibernate.reactive.provider.ReactiveServiceRegistryBuilder;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

@Configuration
@EnableJpaAuditing
@RequiredArgsConstructor
public class PersistenceConfig {

    private final JpaProperties jpaProperties;
    private final PersistencePropertyResolver persistenceProperties;
    private final ResourceLoader resourceLoader;

    // TODO: for testing, replace with JWT implementation
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

    @Bean
    public Mutiny.SessionFactory sessionFactory() {

        var metadataSources = new MetadataSources(new BootstrapServiceRegistryBuilder()
            .applyClassLoader(resourceLoader.getClassLoader())
            .build());

        new PersistenceManagedTypesScanner(resourceLoader)
            .scan("com.streamarr.server.domain")
            .getManagedClassNames()
            .forEach(metadataSources::addAnnotatedClassName);

        var configuration = new org.hibernate.cfg.Configuration(metadataSources);

        var properties = new Properties();

        var springJpaProperties = jpaProperties.getProperties();

        properties.putAll(springJpaProperties);
        properties.put(AvailableSettings.POOL_SIZE, 10);

        properties.put(AvailableSettings.URL, persistenceProperties.getJdbcUrl());
        properties.put(AvailableSettings.USER, persistenceProperties.getUsername());
        properties.put(AvailableSettings.PASS, persistenceProperties.getPassword());
        properties.put(AvailableSettings.DIALECT, PostgreSQL10Dialect.class.getName());

        configuration.addProperties(properties);
        configuration.setPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy());

        var registry = new ReactiveServiceRegistryBuilder()
            .applySettings(configuration.getProperties()).build();

        return configuration.buildSessionFactory(registry).unwrap(Mutiny.SessionFactory.class);
    }
}

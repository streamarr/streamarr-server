package com.streamarr.server.support;

import java.util.List;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.hibernate.jpa.boot.spi.JpaSettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Lets {@link HibernateWorkCounter} see every statement Hibernate prepares and entity it loads. */
@TestConfiguration(proxyBeanMethods = false)
public class HibernateWorkCounterConfiguration {

  @Bean
  HibernateWorkCounter hibernateWorkCounter() {
    return new HibernateWorkCounter();
  }

  @Bean
  HibernatePropertiesCustomizer hibernateWorkCounterHooks(HibernateWorkCounter counter) {
    return properties -> {
      properties.put(JdbcSettings.STATEMENT_INSPECTOR, counter);
      properties.put(
          JpaSettings.INTEGRATOR_PROVIDER,
          (IntegratorProvider) () -> List.of(postLoadListener(counter)));
    };
  }

  private static Integrator postLoadListener(HibernateWorkCounter counter) {
    return new Integrator() {
      @Override
      public void integrate(
          Metadata metadata,
          BootstrapContext bootstrapContext,
          SessionFactoryImplementor sessionFactory) {
        sessionFactory.getEventListenerRegistry().appendListeners(EventType.POST_LOAD, counter);
      }
    };
  }
}

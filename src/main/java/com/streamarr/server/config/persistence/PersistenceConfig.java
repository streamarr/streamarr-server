package com.streamarr.server.config.persistence;

import com.streamarr.server.repositories.audit.TransactionAuditActorBinder;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class PersistenceConfig {

  @Bean
  public AuditActorSource auditActorSource() {
    return new SecurityAuditActorSource();
  }

  // Lazy: the transaction manager resolves this listener while it is being created, and the
  // jOOQ binding depends on the transaction manager.
  @Bean
  public AuditActorTransactionListener auditActorTransactionListener(
      AuditActorSource auditActorSource,
      @Lazy TransactionAuditActorBinder auditActorBinder,
      MeterRegistry meterRegistry) {
    return new AuditActorTransactionListener(auditActorSource, auditActorBinder, meterRegistry);
  }
}

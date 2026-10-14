package com.streamarr.server.config.persistence;

import com.streamarr.server.repositories.audit.TransactionAuditActor;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing
public class PersistenceConfig {

  @Bean
  public AuditorAware<UUID> auditorAware() {
    return new SecurityAuditorAware();
  }

  @Bean
  public AuditActorSource auditActorSource() {
    return new SecurityAuditActorSource();
  }

  // Lazy: the transaction manager resolves this listener while it is being created, and the
  // jOOQ binding depends on the transaction manager.
  @Bean
  public AuditActorTransactionListener auditActorTransactionListener(
      AuditActorSource auditActorSource, @Lazy TransactionAuditActor transactionAuditActor) {
    return new AuditActorTransactionListener(auditActorSource, transactionAuditActor);
  }
}

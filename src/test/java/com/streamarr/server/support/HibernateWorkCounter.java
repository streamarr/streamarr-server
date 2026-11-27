package com.streamarr.server.support;

import com.streamarr.server.services.auth.StreamarrAuthenticationToken;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import lombok.NonNull;
import org.hibernate.event.spi.PostLoadEvent;
import org.hibernate.event.spi.PostLoadEventListener;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Counts the statements Hibernate prepares and the entities it loads for one identity while work
 * runs. Hibernate's own statistics count every thread, so a scheduled job that reads the database
 * during the work adds to them. This counts only threads that act as the identity's session: every
 * data fetcher of a GraphQL operation acts as the identity that opened it, on whichever thread it
 * runs, and scheduled and system work acts as no Account.
 */
public final class HibernateWorkCounter implements StatementInspector, PostLoadEventListener {

  private final AtomicReference<Tally> running = new AtomicReference<>();

  public Counts countAs(@NonNull AuthTestSupport.TestIdentity identity, @NonNull Callable<?> work)
      throws Exception {
    var tally = new Tally(identity.session().getId());
    running.set(tally);
    try {
      work.call();
    } finally {
      running.set(null);
    }

    return tally.counts();
  }

  @Override
  public String inspect(String sql) {
    tallyOfThisThread().ifPresent(tally -> tally.preparedStatements.increment());
    return sql;
  }

  @Override
  public void onPostLoad(PostLoadEvent event) {
    var entityType = event.getPersister().getMappedClass();
    tallyOfThisThread()
        .ifPresent(
            tally ->
                tally.loadedEntities.computeIfAbsent(entityType, _ -> new LongAdder()).increment());
  }

  private Optional<Tally> tallyOfThisThread() {
    return Optional.ofNullable(running.get())
        .filter(tally -> isThisThreadActingAs(tally.authSessionId));
  }

  private static boolean isThisThreadActingAs(UUID authSessionId) {
    return SecurityContextHolder.getContext().getAuthentication()
            instanceof StreamarrAuthenticationToken authentication
        && authentication.getPrincipal().authSessionId().equals(authSessionId);
  }

  public record Counts(long preparedStatements, Map<Class<?>, Long> loadedEntities) {

    public long loaded(Class<?> entityType) {
      return loadedEntities.getOrDefault(entityType, 0L);
    }
  }

  private static final class Tally {

    private final UUID authSessionId;
    private final LongAdder preparedStatements = new LongAdder();
    private final Map<Class<?>, LongAdder> loadedEntities = new ConcurrentHashMap<>();

    private Tally(UUID authSessionId) {
      this.authSessionId = authSessionId;
    }

    private Counts counts() {
      return new Counts(
          preparedStatements.sum(),
          loadedEntities.entrySet().stream()
              .collect(
                  Collectors.toUnmodifiableMap(
                      Map.Entry::getKey, entry -> entry.getValue().sum())));
    }
  }
}

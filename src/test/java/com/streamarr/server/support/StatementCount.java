package com.streamarr.server.support;

import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.hibernate.SessionFactory;

/**
 * Counts the SQL statements one request runs, so a test can pin that a read does not grow with its
 * rows: those Hibernate runs and those jOOQ runs itself.
 */
@RequiredArgsConstructor
public class StatementCount {

  private static final int COUNTED_RUNS = 3;

  private final EntityManagerFactory entityManagerFactory;
  private final JooqStatementCounter jooqStatements;

  /** Something a test does whose statements it counts. */
  @FunctionalInterface
  public interface Request {
    void run() throws Exception;
  }

  // Both counts are global, so the count holds only while no other test uses the context.
  // The session reaper also reads the server settings every few seconds on its own thread, so a
  // run can count one statement that is not the request's; the fewest over a few runs is its own.
  public long statementsWhile(Request request) throws Exception {
    var fewest = Long.MAX_VALUE;
    for (var run = 0; run < COUNTED_RUNS; run++) {
      fewest = Math.min(fewest, statementsOfOneRun(request));
    }

    return fewest;
  }

  private long statementsOfOneRun(Request request) throws Exception {
    var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    var previouslyEnabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    statistics.clear();
    var jooqBefore = jooqStatements.executed();

    try {
      request.run();
      return statistics.getPrepareStatementCount() + jooqStatements.executed() - jooqBefore;
    } finally {
      statistics.clear();
      statistics.setStatisticsEnabled(previouslyEnabled);
    }
  }
}

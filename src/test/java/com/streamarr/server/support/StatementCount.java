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

  /** What a test sets up, uncounted, before each counted run of its request. */
  @FunctionalInterface
  public interface Arrangement<T> {
    T arrange() throws Exception;
  }

  /** Something a test does with what it arranged, whose statements it counts. */
  @FunctionalInterface
  public interface ArrangedRequest<T> {
    void run(T arranged) throws Exception;
  }

  /**
   * The fewest statements that one of a few runs of the request took. Every run must find what the
   * first run found. A request that changes what the next run finds, such as a write, arranges each
   * run with {@link #statementsWhile(Arrangement, ArrangedRequest)} instead.
   */
  public long statementsWhile(Request request) throws Exception {
    return statementsWhile(() -> request, Request::run);
  }

  /**
   * The fewest statements that one of a few runs of the request took. Before each run, the
   * arrangement sets up what that run gets, and the arrangement's statements are not counted.
   */
  public <T> long statementsWhile(Arrangement<T> arrangement, ArrangedRequest<T> request)
      throws Exception {
    // Both counts are global, so the count holds only while no other test uses the context.
    // The session reaper also reads the server settings every few seconds on its own thread, so
    // a run can count one statement that is not the request's. The fewest over a few runs is the
    // request's own.
    var fewest = Long.MAX_VALUE;
    for (var run = 0; run < COUNTED_RUNS; run++) {
      var arranged = arrangement.arrange();
      fewest = Math.min(fewest, statementsOfOneRun(() -> request.run(arranged)));
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

package com.streamarr.server.support;

import java.util.LinkedHashSet;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;
import org.jooq.DSLContext;
import org.jooq.Select;
import org.jooq.VisitListener;
import org.jooq.impl.DSL;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Captures the jOOQ selects that work renders on the calling thread, then returns each one's {@code
 * EXPLAIN (ANALYZE, FORMAT JSON)} plan so a test can bound the rows a query touches.
 */
@Builder
public final class AnalyzedSelects {
  @NonNull private final DSLContext dsl;
  @NonNull private final JdbcTemplate jdbc;
  @NonNull private final ObjectMapper json;

  public List<JsonNode> of(Runnable work) {
    var statements = new LinkedHashSet<String>();
    var caller = Thread.currentThread();
    var previousListeners = dsl.configuration().visitListenerProviders();
    dsl.configuration()
        .set(
            VisitListener.onVisitStart(
                context -> {
                  if (Thread.currentThread() != caller) {
                    return;
                  }

                  if (context.queryPartsLength() == 1 && context.queryPart() instanceof Select<?>) {
                    statements.add(DSL.using(dsl.dialect()).renderInlined(context.queryPart()));
                  }
                }));
    try {
      work.run();
    } finally {
      dsl.configuration().set(previousListeners);
    }

    return statements.stream()
        .map(
            statement ->
                json.readTree(
                    jdbc.queryForObject(
                        "EXPLAIN (ANALYZE, FORMAT JSON) " + statement, String.class)))
        .toList();
  }
}

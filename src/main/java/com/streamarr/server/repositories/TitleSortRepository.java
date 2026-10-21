package com.streamarr.server.repositories;

import static org.jooq.impl.DSL.unnest;

import java.util.Collection;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class TitleSortRepository {
  private final DSLContext context;

  public Map<String, String> normalize(Collection<String> titles) {
    if (titles.isEmpty()) {
      return Map.of();
    }

    var values = unnest(titles.toArray(String[]::new)).as("titles", "title");
    var title = values.field("title", String.class);
    var normalized = JooqQueryHelper.normalizeTitle(title);
    return context.select(title, normalized).from(values).fetchMap(title, normalized);
  }
}

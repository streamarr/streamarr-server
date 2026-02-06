package com.streamarr.server.repositories;

import jakarta.persistence.EntityManager;
import java.util.List;
import lombok.experimental.UtilityClass;

@UtilityClass
public class JooqQueryHelper {

  @SuppressWarnings("unchecked")
  public <E> List<E> nativeQuery(EntityManager em, org.jooq.Query query, Class<E> type) {

    // Extract the SQL statement from the jOOQ query:
    var result = em.createNativeQuery(query.getSQL(), type);

    // Extract the bind values from the jOOQ query:
    List<Object> values = query.getBindValues();
    for (int i = 0; i < values.size(); i++) {
      result.setParameter(i + 1, values.get(i));
    }

    return result.getResultList();
  }
}

package com.streamarr.server.repositories;

import static org.jooq.impl.DSL.noCondition;

import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.jooq.generated.Tables;
import jakarta.persistence.EntityManager;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.jooq.Condition;

@UtilityClass
public class JooqQueryHelper {

  @SuppressWarnings("unchecked")
  public <E> List<E> nativeQuery(EntityManager em, org.jooq.Query query, Class<E> type) {
    var result = em.createNativeQuery(query.getSQL(), type);

    List<Object> values = query.getBindValues();
    for (int i = 0; i < values.size(); i++) {
      result.setParameter(i + 1, values.get(i));
    }

    return result.getResultList();
  }

  public Condition startLetterCondition(AlphabetLetter startLetter) {
    if (startLetter == null) {
      return noCondition();
    }

    if (startLetter == AlphabetLetter.HASH) {
      return Tables.BASE_COLLECTABLE.TITLE_SORT.lessThan("a");
    }

    return Tables.BASE_COLLECTABLE.TITLE_SORT.greaterOrEqual(startLetter.name().toLowerCase());
  }
}

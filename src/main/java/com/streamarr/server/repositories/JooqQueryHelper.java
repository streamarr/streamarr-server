package com.streamarr.server.repositories;

import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.left;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.noCondition;

import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.jooq.generated.Tables;
import jakarta.persistence.EntityManager;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.jooq.Condition;
import org.jooq.SortOrder;

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

  public Condition startLetterCondition(AlphabetLetter startLetter, SortOrder direction) {
    if (startLetter == null) {
      return noCondition();
    }

    if (startLetter == AlphabetLetter.HASH) {
      return noCondition();
    }

    var letter = startLetter.name().toLowerCase();
    var firstCharLower = lower(left(Tables.BASE_COLLECTABLE.TITLE_SORT, 1));

    return firstCharLower.greaterOrEqual(inline(letter));
  }
}

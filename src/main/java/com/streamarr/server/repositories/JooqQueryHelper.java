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

    var firstCharLower = lower(left(Tables.BASE_COLLECTABLE.TITLE_SORT, 1));

    if (startLetter == AlphabetLetter.HASH) {
      return firstCharLower.lt(inline("a")).or(firstCharLower.gt(inline("z")));
    }

    var letter = startLetter.name().toLowerCase();

    if (startLetter == AlphabetLetter.Z) {
      return firstCharLower.eq(inline(letter));
    }

    var nextLetter = String.valueOf((char) (letter.charAt(0) + 1));
    return firstCharLower
        .greaterOrEqual(inline(letter))
        .and(firstCharLower.lessThan(inline(nextLetter)));
  }
}

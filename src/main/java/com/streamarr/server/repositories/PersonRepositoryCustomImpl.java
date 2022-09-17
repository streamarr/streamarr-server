package com.streamarr.server.repositories;

import com.streamarr.server.domain.metadata.Person;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.vertx.UniHelper;
import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.hibernate.reactive.mutiny.Mutiny;

import java.util.Arrays;
import java.util.List;


@RequiredArgsConstructor
public class PersonRepositoryCustomImpl implements PersonRepositoryCustom {

    private final Mutiny.SessionFactory sessionFactory;

    public Future<List<Person>> saveAllAsync(List<Person> people) {

        Person[] personArray = people.toArray(new Person[0]);
        return UniHelper.toFuture(sessionFactory.withTransaction(session -> {
            session.persistAll(personArray)
                .chain(session::flush);
            return Uni.createFrom().item(Arrays.asList(personArray));
        }));
    }
}

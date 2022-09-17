package com.streamarr.server.repositories;

import com.streamarr.server.domain.metadata.Person;
import io.vertx.core.Future;

import java.util.List;

public interface PersonRepositoryCustom {

    Future<List<Person>> saveAllAsync(List<Person> people);

}

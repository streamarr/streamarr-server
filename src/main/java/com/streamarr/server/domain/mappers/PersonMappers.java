package com.streamarr.server.domain.mappers;

import com.streamarr.server.domain.metadata.Person;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface PersonMappers {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdOn", ignore = true)
    @Mapping(target = "movies", ignore = true)
    void updatePerson(Person source, @MappingTarget Person target);

}

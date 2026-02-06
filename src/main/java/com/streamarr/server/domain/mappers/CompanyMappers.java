package com.streamarr.server.domain.mappers;

import com.streamarr.server.domain.metadata.Company;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface CompanyMappers {

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "createdBy", ignore = true)
  @Mapping(target = "createdOn", ignore = true)
  @Mapping(target = "movies", ignore = true)
  void updateCompany(Company source, @MappingTarget Company target);
}

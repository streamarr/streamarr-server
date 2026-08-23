package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PaginationOptions;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("User Account Repository Integration Tests")
class UserAccountRepositoryIT extends AbstractIntegrationTest {

  @Autowired private UserAccountRepository repository;

  @Test
  @DisplayName("Should return no administration pages when no Households are requested")
  void shouldReturnNoAdministrationPagesWhenNoHouseholdsRequested() {
    var options =
        MediaPaginationOptions.builder()
            .paginationOptions(PaginationOptions.builder().limit(10).build())
            .mediaFilter(MediaFilter.builder().build())
            .build();

    assertThat(repository.findAdministrationPages(Set.of(), options)).isEmpty();
  }
}

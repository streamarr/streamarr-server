package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.fakes.FakeCompanyRepository;
import com.streamarr.server.fixtures.ArtworkFixture;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Company Service Tests")
class CompanyServiceTest {

  private FakeCompanyRepository companyRepository;
  private ArtworkFixture artwork;
  private CompanyService companyService;

  @BeforeEach
  void setUp() {
    companyRepository = new FakeCompanyRepository();
    artwork = ArtworkFixture.builder().build();
    artwork.scheduling().holdScheduledSteps();
    companyService = new CompanyService(companyRepository, artwork.artworkService());
  }

  @Test
  @DisplayName(
      "Should insert companies in ascending source id order when they are listed in another order")
  void shouldInsertCompaniesInAscendingSourceIdOrderWhenTheyAreListedInAnotherOrder() {
    var insertOrderRepository = new InsertOrderCompanyRepository();
    var companies = new LinkedHashSet<Company>();
    companies.add(Company.builder().name("Company 300").sourceId("300").build());
    companies.add(Company.builder().name("Company 100").sourceId("100").build());
    companies.add(Company.builder().name("Company 200").sourceId("200").build());

    new CompanyService(insertOrderRepository, ArtworkFixture.builder().build().artworkService())
        .getOrCreateCompanies(companies, Map.of(), ImageRefreshMode.PRESERVE);

    assertThat(insertOrderRepository.insertedSourceIds()).containsExactly("100", "200", "300");
  }

  @Test
  @DisplayName("Should create new company when source ID not found")
  void shouldCreateNewCompanyWhenSourceIdNotFound() {
    var company = Company.builder().name("Warner Bros.").sourceId("wb-123").build();

    var result =
        companyService.getOrCreateCompanies(Set.of(company), Map.of(), ImageRefreshMode.PRESERVE);

    assertThat(result).hasSize(1);
    var saved = result.iterator().next();
    assertThat(saved.getName()).isEqualTo("Warner Bros.");
    assertThat(saved.getSourceId()).isEqualTo("wb-123");
    assertThat(saved.getId()).isNotNull();
    assertThat(companyRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should update existing company when source ID already exists")
  void shouldUpdateExistingCompanyWhenSourceIdAlreadyExists() {
    var existing =
        companyRepository.save(Company.builder().name("Old Name").sourceId("studio-1").build());

    var updated = Company.builder().name("New Name").sourceId("studio-1").build();

    var result =
        companyService.getOrCreateCompanies(Set.of(updated), Map.of(), ImageRefreshMode.PRESERVE);

    assertThat(result).hasSize(1);
    var returned = result.iterator().next();
    assertThat(returned.getId()).isEqualTo(existing.getId());
    assertThat(returned.getName()).isEqualTo("New Name");
    assertThat(companyRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should update existing company when batch contains known source ID")
  void shouldUpdateExistingCompanyWhenBatchContainsKnownSourceId() {
    var existing =
        companyRepository.save(
            Company.builder().name("Existing Studio").sourceId("existing-1").build());

    var updatedExisting = Company.builder().name("Updated Studio").sourceId("existing-1").build();
    var brandNew = Company.builder().name("Brand New Studio").sourceId("new-1").build();

    var result =
        companyService.getOrCreateCompanies(
            Set.of(updatedExisting, brandNew), Map.of(), ImageRefreshMode.PRESERVE);

    var returnedExisting =
        result.stream().filter(c -> "existing-1".equals(c.getSourceId())).findFirst().orElseThrow();
    assertThat(returnedExisting.getId()).isEqualTo(existing.getId());
    assertThat(returnedExisting.getName()).isEqualTo("Updated Studio");
  }

  @Test
  @DisplayName("Should create new company when batch contains unknown source ID")
  void shouldCreateNewCompanyWhenBatchContainsUnknownSourceId() {
    companyRepository.save(
        Company.builder().name("Existing Studio").sourceId("existing-1").build());

    var updatedExisting = Company.builder().name("Updated Studio").sourceId("existing-1").build();
    var brandNew = Company.builder().name("Brand New Studio").sourceId("new-1").build();

    var result =
        companyService.getOrCreateCompanies(
            Set.of(updatedExisting, brandNew), Map.of(), ImageRefreshMode.PRESERVE);

    var returnedNew =
        result.stream().filter(c -> "new-1".equals(c.getSourceId())).findFirst().orElseThrow();
    assertThat(returnedNew.getName()).isEqualTo("Brand New Studio");
    assertThat(returnedNew.getId()).isNotNull();
    assertThat(companyRepository.count()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should request background artwork when creating new company with image sources")
  void shouldRequestBackgroundArtworkWhenCreatingNewCompanyWithImageSources() {
    var company = Company.builder().name("Warner Bros.").sourceId("wb-123").build();
    var imageSources =
        Map.<String, List<ImageSource>>of(
            "wb-123", List.of(new TmdbImageSource(ImageType.LOGO, "/wb.png")));

    companyService.getOrCreateCompanies(Set.of(company), imageSources, ImageRefreshMode.PRESERVE);

    var requests = artwork.requests().requests();
    assertThat(requests).hasSize(1);
    assertThat(requests.getFirst().artwork().targetType()).isEqualTo(ImageEntityType.COMPANY);
  }

  @Test
  @DisplayName("Should request artwork when existing company has image sources")
  void shouldRequestArtworkWhenExistingCompanyHasImageSources() {
    var existing =
        companyRepository.save(Company.builder().name("Existing").sourceId("wb-123").build());

    var updated = Company.builder().name("Updated").sourceId("wb-123").build();
    var expectedImageSource = new TmdbImageSource(ImageType.LOGO, "/wb.png");
    var imageSources = Map.<String, List<ImageSource>>of("wb-123", List.of(expectedImageSource));

    companyService.getOrCreateCompanies(Set.of(updated), imageSources, ImageRefreshMode.PRESERVE);

    var requests = artwork.requests().requests();
    assertThat(requests)
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.artwork().targetId()).isEqualTo(existing.getId());
              assertThat(request.artwork().targetType()).isEqualTo(ImageEntityType.COMPANY);
              assertThat(request.artwork().sources()).containsExactly(expectedImageSource);
              assertThat(request.refreshMode()).isEqualTo(ImageRefreshMode.PRESERVE);
            });
  }

  @Test
  @DisplayName("Should propagate image refresh mode when requesting company artwork")
  void shouldPropagateImageRefreshModeWhenRequestingCompanyArtwork() {
    var company = Company.builder().name("Warner Bros.").sourceId("wb-123").build();
    var imageSources =
        Map.<String, List<ImageSource>>of(
            "wb-123", List.of(new TmdbImageSource(ImageType.LOGO, "/wb.png")));

    companyService.getOrCreateCompanies(
        Set.of(company), imageSources, ImageRefreshMode.FORCE_REFRESH);

    assertThat(artwork.requests().requests())
        .singleElement()
        .extracting(PendingArtworkRequest::refreshMode)
        .isEqualTo(ImageRefreshMode.FORCE_REFRESH);
  }

  @Test
  @DisplayName("Should not request artwork when existing company has no image sources")
  void shouldNotRequestArtworkWhenExistingCompanyHasNoImageSources() {
    companyRepository.save(Company.builder().name("Existing").sourceId("wb-123").build());
    var updated = Company.builder().name("Updated").sourceId("wb-123").build();

    companyService.getOrCreateCompanies(Set.of(updated), Map.of(), ImageRefreshMode.PRESERVE);

    assertThat(artwork.requests().requests()).isEmpty();
  }

  @Test
  @DisplayName("Should throw when company source ID is null")
  void shouldThrowWhenCompanySourceIdIsNull() {
    var company = Company.builder().name("Warner Bros.").sourceId(null).build();
    var companies = Set.of(company);

    var emptyImageSources = Map.<String, List<ImageSource>>of();

    assertThatThrownBy(
            () ->
                companyService.getOrCreateCompanies(
                    companies, emptyImageSources, ImageRefreshMode.PRESERVE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should return empty set when input is null")
  void shouldReturnEmptySetWhenInputIsNull() {
    var result = companyService.getOrCreateCompanies(null, Map.of(), ImageRefreshMode.PRESERVE);

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should throw when company not found after upsert")
  void shouldThrowWhenCompanyNotFoundAfterUpsert() {
    var stubRepository = mock(CompanyRepository.class);
    when(stubRepository.findBySourceId("wb-123")).thenReturn(Optional.empty());
    var service =
        new CompanyService(stubRepository, ArtworkFixture.builder().build().artworkService());

    var company = Company.builder().name("Warner Bros.").sourceId("wb-123").build();
    var companies = Set.of(company);

    Map<String, List<ImageSource>> emptyImageSources = Map.of();
    assertThatThrownBy(
            () ->
                service.getOrCreateCompanies(
                    companies, emptyImageSources, ImageRefreshMode.PRESERVE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not found after upsert");
  }

  private static final class InsertOrderCompanyRepository extends FakeCompanyRepository {

    private final List<String> insertedSourceIds = new ArrayList<>();

    @Override
    public boolean insertIfAbsent(String sourceId, String name) {
      insertedSourceIds.add(sourceId);
      return super.insertIfAbsent(sourceId, name);
    }

    private List<String> insertedSourceIds() {
      return List.copyOf(insertedSourceIds);
    }
  }
}

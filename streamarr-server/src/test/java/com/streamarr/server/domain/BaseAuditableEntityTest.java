package com.streamarr.server.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.metadata.Review;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Base Auditable Entity Tests")
class BaseAuditableEntityTest {

  @Nested
  @DisplayName("Base Entity Equals Tests")
  class EqualsTest {

    @Test
    @DisplayName("Should return true when comparing the exact same instance")
    void shouldReturnTrueWhenComparingTheExactSameInstance() {
      var review = Review.builder().build();
      var sameInstance = review;

      assertThat(review).isEqualTo(sameInstance);
    }

    @Test
    @DisplayName("Should return false when object has a null ID")
    void shouldReturnFalseWhenObjectHasNullId() {
      var review1 = Review.builder().build();
      var review2 = Review.builder().build();

      assertThat(review1).isNotEqualTo(review2);
    }

    @Test
    @DisplayName("Should return false when objects have different IDs")
    void shouldReturnFalseWhenObjectsHaveDifferentIds() {
      var review1 = Review.builder().id(UUID.randomUUID()).build();
      var review2 = Review.builder().id(UUID.randomUUID()).build();

      assertThat(review1).isNotEqualTo(review2);
    }

    @Test
    @DisplayName("Should return true when objects of the same runtime class have the same IDs")
    void shouldReturnTrueWhenObjectsOfTheSameRuntimeClassHaveTheSameIds() {
      var id = UUID.randomUUID();
      var review1 = Review.builder().id(id).build();
      var review2 = Review.builder().id(id).build();

      assertThat(review1).isEqualTo(review2);
    }
  }

  @Nested
  @DisplayName("Base Entity Hash Code Tests")
  class HashCodeTest {

    @Test
    @DisplayName("Should return correct hash code for class extension")
    void shouldReturnCorrectHashCodeForClassExtension() {
      var review = Review.builder().build();

      assertThat(review).hasSameHashCodeAs(Review.class);
    }
  }

  @Nested
  @DisplayName("Base Entity Builder Tests")
  class BuilderTest {

    @Test
    @DisplayName("Should ignore createdOn when set via builder")
    void shouldIgnoreCreatedOnWhenSetViaBuilder() throws Exception {
      var builder = Review.builder();
      var createdOnMethod =
          BaseAuditableEntity.BaseAuditableEntityBuilder.class.getDeclaredMethod(
              "createdOn", Instant.class);

      createdOnMethod.setAccessible(true);
      createdOnMethod.invoke(builder, Instant.parse("2020-01-01T00:00:00Z"));
      var review = (Review) builder.build();

      assertThat(review.getCreatedOn()).isNull();
    }

    @Test
    @DisplayName("Should ignore lastModifiedOn when set via builder")
    void shouldIgnoreLastModifiedOnWhenSetViaBuilder() throws Exception {
      var builder = Review.builder();
      var lastModifiedOnMethod =
          BaseAuditableEntity.BaseAuditableEntityBuilder.class.getDeclaredMethod(
              "lastModifiedOn", Instant.class);

      lastModifiedOnMethod.setAccessible(true);
      lastModifiedOnMethod.invoke(builder, Instant.parse("2020-01-01T00:00:00Z"));
      var review = (Review) builder.build();

      assertThat(review.getLastModifiedOn()).isNull();
    }
  }
}

package com.streamarr.server.domain;

import com.streamarr.server.domain.metadata.Review;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("UnitTest")
@DisplayName("Base Entity Tests")
public class BaseEntityTest {

    @Nested
    @DisplayName("Base Entity Equals Tests")
    public class EqualsTest {

        @Test
        @DisplayName("Should return true when comparing the exact same instance")
        public void shouldReturnTrueWhenComparingTheExactSameInstance() {
            var review = Review.builder().build();

            assertThat(review.equals(review)).isTrue();
        }

        @Test
        @DisplayName("Should return false when object has a null ID")
        public void shouldReturnFalseWhenObjectHasNullId() {
            var review1 = Review.builder().build();
            var review2 = Review.builder().build();

            assertThat(review1.equals(review2)).isFalse();
        }

        @Test
        @DisplayName("Should return false when objects have different IDs")
        public void shouldReturnFalseWhenObjectsHaveDifferentIds() {
            var review1 = Review.builder().id(UUID.randomUUID()).build();
            var review2 = Review.builder().id(UUID.randomUUID()).build();

            assertThat(review1.equals(review2)).isFalse();
        }

        @Test
        @DisplayName("Should return true when objects of the same runtime class have the same IDs")
        public void shouldReturnTrueWhenObjectsOfTheSameRuntimeClassHaveTheSameIds() {
            var id = UUID.randomUUID();
            var review1 = Review.builder().id(id).build();
            var review2 = Review.builder().id(id).build();

            assertThat(review1.equals(review2)).isTrue();
        }
    }

    @Nested
    @DisplayName("Base Entity Hash Code Tests")
    public class HashCodeTest {

        @Test
        @DisplayName("Should return correct hash code for class extension")
        public void shouldReturnCorrectHashCodeForClassExtension() {
            var review = Review.builder().build();

            assertThat(review.hashCode()).isEqualTo(Review.class.hashCode());
        }
    }

    @Nested
    @DisplayName("Base Entity Builder Tests")
    public class BuilderTest {

        @Test
        @DisplayName("Should throw exception when createdOn builder accessed")
        public void shouldThrowExceptionWhenCreatedOnBuilderAccessed() throws Exception {
            var builder = Review.builder();
            var createdOnMethod = BaseEntity.BaseEntityBuilder.class.getDeclaredMethod("createdOn", Instant.class);

            createdOnMethod.setAccessible(true);
            var invocationEx = assertThrows(
                InvocationTargetException.class,
                () -> createdOnMethod.invoke(builder, Instant.now()));

            assertThat(invocationEx.getCause()).isInstanceOf(UnsupportedOperationException.class);
            assertThat(invocationEx.getCause()).hasMessage("createdOn method is unsupported");
        }

        @Test
        @DisplayName("Should throw exception when lastModifiedOn builder accessed")
        public void shouldThrowExceptionWhenLastModifiedOnBuilderAccessed() throws Exception {
            var builder = Review.builder();
            var lastModifiedOnMethod = BaseEntity.BaseEntityBuilder.class.getDeclaredMethod("lastModifiedOn", Instant.class);

            lastModifiedOnMethod.setAccessible(true);
            var invocationEx = assertThrows(
                InvocationTargetException.class,
                () -> lastModifiedOnMethod.invoke(builder, Instant.now()));

            assertThat(invocationEx.getCause()).isInstanceOf(UnsupportedOperationException.class);
            assertThat(invocationEx.getCause()).hasMessage("lastModifiedOn method is unsupported");
        }
    }
}

package com.streamarr.server.services.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("UnitTest")
@DisplayName("Video Extension Validation Tests")
public class VideoExtensionValidatorTest {

    private final VideoExtensionValidator videoExtensionValidator = new VideoExtensionValidator();

    @Test
    @DisplayName("Should successfully validate when given valid extension")
    void shouldValidateSuccessfully() {
        assertTrue(videoExtensionValidator.validate("mp4"));
    }

    @Test
    @DisplayName("Should fail validation when given invalid extension")
    void shouldFailValidation() {
        assertFalse(videoExtensionValidator.validate("iso"));
    }
}

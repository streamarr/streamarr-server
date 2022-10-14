package com.streamarr.server.services.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.http.HttpClient;

@DisplayName("The Movie Database Http Service Tests")
@ExtendWith(MockitoExtension.class)
public class TheMovieDatabaseHttpServiceTest {

    @Mock
    private HttpClient mockHttpClient;

    @Mock
    private Logger log;

    @InjectMocks
    private TheMovieDatabaseHttpService theMovieDatabaseHttpService;

    @Test
    @DisplayName("Should .")
    void should() throws IOException, InterruptedException {
        return;
    }
}

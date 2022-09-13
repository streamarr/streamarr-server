//package com.streamarr.server.services.metadata;
//
//import akka.actor.ActorSystem;
//import akka.http.javadsl.Http;
//import akka.http.javadsl.model.HttpRequest;
//import akka.http.javadsl.model.HttpResponse;
//import akka.testkit.javadsl.TestKit;
//import com.streamarr.server.services.HttpFactory;
//import com.streamarr.server.services.extraction.video.VideoFilenameExtractionService;
//import org.junit.jupiter.api.AfterAll;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Tag;
//import org.junit.jupiter.api.Test;
//
//import java.util.concurrent.CompletableFuture;
//
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.Mockito.mock;
//import static org.mockito.Mockito.when;
//
//@Tag("UnitTest")
//@DisplayName("The Movie Database Service Tests")
//public class TheMovieDatabaseServiceTest {
//
//    private final Http mockHttp = mock(Http.class);
//    private final HttpFactory mockHttpFactory = mock(HttpFactory.class);
//    private final static ActorSystem actorSystem = ActorSystem.create();
//
//    private TheMovieDatabaseService theMovieDatabaseService = new TheMovieDatabaseService(actorSystem, mockHttpFactory, "test");
//
//    private final static TestKit testKit = new TestKit(actorSystem);
//
//    @AfterAll
//    public static void teardown() {
//        TestKit.shutdownActorSystem(actorSystem);
//    }
//
//    @Test
//    @DisplayName("Should ")
//    void should() {
//
//        when(mockHttpFactory.createHttpClient()).thenReturn(mockHttp);
//
//        when(mockHttp.singleRequest(any(HttpRequest.class))).thenReturn(CompletableFuture.completedFuture(HttpResponse.create().withStatus(500)));
//
//        var response = theMovieDatabaseService.searchForMovie(VideoFilenameExtractionService.Result.builder().title("About Time").year("2013").build());
//
//        response.whenComplete((a, b) -> {
//            System.out.println(b.getMessage());
//            System.out.println(a.getTotalResults());
//        });
//    }
//
//}

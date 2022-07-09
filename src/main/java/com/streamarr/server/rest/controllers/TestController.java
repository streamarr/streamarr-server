package com.streamarr.server.rest.controllers;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.repositories.BaseCollectableRepository;
import com.streamarr.server.repositories.movie.MovieRepository;
import com.streamarr.server.services.library.video.VideoResolutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class TestController {

    private final VideoResolutionService videoResolutionService;
    private final MovieRepository movieRepository;
    private final BaseCollectableRepository baseCollectableRepository;

    // TODO: Delete. Using for executing some logic.
    @PostMapping("/test")
    public void test() {
        videoResolutionService.parsePaths();
    }

    @PostMapping("/base")
    public List<BaseCollectable> base() {
        return baseCollectableRepository.getBaseCollectableEntities();
    }

    @PostMapping("baseAll")
    public List<BaseCollectable> baseAll() {
        return baseCollectableRepository.findAll();
    }

    @PostMapping("createMovie")
    public Movie createMovie(Movie movie) {
        return movieRepository.save(Movie.builder()
            .artwork("/path/art-4.png")
            .contentRating("R")
            .title("The Matrix Revolutions")
            .libraryId(UUID.fromString("41b306af-59d0-43f0-af6d-d967592aeb18"))
            .build());
    }
}

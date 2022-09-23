package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.MediaFile;
import io.smallrye.mutiny.vertx.UniHelper;
import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.hibernate.reactive.mutiny.Mutiny;
import org.slf4j.Logger;


@RequiredArgsConstructor
public class MediaFileRepositoryCustomImpl implements MediaFileRepositoryCustom {

    private final Mutiny.SessionFactory sessionFactory;
    private final Logger log;

    public Future<MediaFile> saveAsync(MediaFile mediaFile) {

        if (mediaFile.getId() == null) {
            return UniHelper.toFuture(sessionFactory.withTransaction(session ->
                session.persist(mediaFile)
                    .chain(session::flush)
                    .replaceWith(mediaFile)
            )).onFailure(f -> log.error("Failure saving MediaFile:", f));
        } else {
            return UniHelper.toFuture(sessionFactory.withSession(session ->
                session.merge(mediaFile)
                    .onItem()
                    .call(session::flush)));
        }

    }
}

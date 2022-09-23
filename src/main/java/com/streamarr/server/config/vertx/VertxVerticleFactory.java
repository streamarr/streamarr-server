package com.streamarr.server.config.vertx;

import io.vertx.core.Promise;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.spi.VerticleFactory;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;

@Component
public class VertxVerticleFactory implements VerticleFactory, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public String prefix() {
        return "streamarr";
    }

    @Override
    public void init(Vertx vertx) {
        // unused, we only need to implement createVerticle()
    }

    @Override
    public void createVerticle(String verticleName, ClassLoader classLoader, Promise<Callable<Verticle>> promise) {
        // use the class name as verticle name
        var clazz = VerticleFactory.removePrefix(verticleName);
        promise.complete(() -> (Verticle) applicationContext.getBean(Class.forName(clazz)));
    }

    @Override
    public void close() {
        // unused, we didn't open any closeable resources
    }

    @Override
    public void setApplicationContext(@NotNull ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}

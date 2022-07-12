package com.streamarr.server.config.akka;

import akka.actor.Actor;
import akka.actor.IndirectActorProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;

@RequiredArgsConstructor
public class SpringActorProducer implements IndirectActorProducer {

    private final ApplicationContext applicationContext;
    private final String beanActorName;

    @Override
    public Actor produce() {
        return (Actor) applicationContext.getBean(beanActorName);
    }

    @Override
    public Class<? extends Actor> actorClass() {
        return (Class<? extends Actor>) applicationContext
            .getType(beanActorName);
    }
}

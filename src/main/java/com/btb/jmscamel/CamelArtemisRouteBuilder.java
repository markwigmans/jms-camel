package com.btb.jmscamel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.redis.processor.idempotent.RedisStringIdempotentRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class CamelArtemisRouteBuilder extends RouteBuilder {

    private final RedisTemplate<String, String> redisTemplate;

    public void configure() {
        RedisStringIdempotentRepository redisIdRepository = new RedisStringIdempotentRepository(redisTemplate,"jms-messages");
        redisIdRepository.setExpiry(60);

        // Send a message to a queue every 5 seconds
        from("timer:mytimer?period=5000").routeId("generate-route")
                .transform().constant("HELLO from Camel!")
                .to("jms:queue:INCOMING");

        // Add unique ID to jms message and send it multiple times to the next queue
        from("jms:queue:INCOMING").routeId("add-id-route")
                .process(exchange -> {
                    String uniqueId = UUID.randomUUID().toString();
                    exchange.getIn().setHeader("UniqueID", uniqueId);
                })
                .loop(3).to("jms:queue:NEXT");

        // filter all unique messages and send them to the queue 'unique'
        from("jms:queue:NEXT").routeId("filter-route")
                .log("Received a message from NEXT - id:'${header.UniqueID}', body:'${body}'")
                .idempotentConsumer(header("UniqueID"), redisIdRepository)
                .process(exchange -> log.info("This message is being processed the first time id:'{}'.", exchange.getIn().getHeader("UniqueID")))
                .to("jms:queue:UNIQUE");

        // Receive the message from the queue and make them processed.
        from("jms:queue:UNIQUE").routeId("receive-route")
                .log("Received a message from UNIQUE - id:'${header.UniqueID}', body:'${body}'")
                .to("jms:queue:PROCESSED");
    }
}
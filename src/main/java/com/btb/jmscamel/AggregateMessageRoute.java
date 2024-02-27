package com.btb.jmscamel;

import io.netty.util.internal.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.redis.processor.aggregate.RedisAggregationRepository;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.spi.AggregationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = "jc.aggregate.enabled", havingValue = "true", matchIfMissing = true)
public class AggregateMessageRoute extends RouteBuilder {

    private final ProducerTemplate producerTemplate;
    private final AtomicInteger counter = new AtomicInteger();

    @Value("${jc.aggregate.redis.endpoint:127.0.0.1:6379}")
    private String endpoint;

    @Value("${jc.aggregate.timer.period:5000}")
    private int period;

    @Value("${jc.aggregate.completion.timeout.sec:60}")
    private int completionTimeout;

    @Value("${jc.aggregate.range.start:5}")
    private int startRange;
    @Value("${jc.aggregate.range.end:10}")
    private int endRange;

    /**
     *
     */
    public void configure() {
        AggregationRepository repository = new RedisAggregationRepository("jms-aggregate", endpoint);

        // Send a message to a queue every X period
        from(String.format("timer:aggregate.testTimer?period=%d", period)).routeId("aggregate.generate-route")
                .process(exchange -> {
                    final String id = UUID.randomUUID().toString();
                    IntStream.range(0, ThreadLocalRandom.current().nextInt(startRange, endRange)).forEach(i -> {
                        MyMessage message = new MyMessage(id, Integer.toString(counter.incrementAndGet()));
                        var newExchange = exchange.copy();
                        newExchange.getIn().setBody(message);
                        producerTemplate.send("direct:aggregate.marshalToJson", newExchange);
                    });
                });

        from("direct:aggregate.marshalToJson").marshal().json(JsonLibrary.Jackson).to("jms:queue:A_INCOMING");

        from("jms:queue:A_INCOMING").routeId("aggregate.aggregate-route")
                .unmarshal().json(JsonLibrary.Jackson, MyMessage.class)
                .aggregate(simple("${body.id}"), new MyAggregationStrategy())
                .aggregationRepository(repository)
                .completionTimeout(TimeUnit.MILLISECONDS.convert(completionTimeout, TimeUnit.SECONDS))
                .parallelProcessing()
                .process(exchange -> {
                    final List<MyMessage> messages = exchange.getIn().getBody(List.class);
                    log.debug("Aggregated Messages: {}", messages);
                    if (!messages.isEmpty()) {
                        String id = messages.get(0).id();
                        List<String> data = messages.stream().map(MyMessage::data).toList();
                        exchange.getIn().setBody(new MyMessages(id,data));
                    }
                })
                .marshal().json(JsonLibrary.Jackson)
                .to("jms:queue:A_AGGREGATED");

        from("jms:queue:A_AGGREGATED").routeId("aggregate.receive-route")
                .log("Received a message from AGGREGATED - body:'${body}'")
                .to("jms:queue:PROCESSED");
}

    static class MyAggregationStrategy implements AggregationStrategy {

        @Override
        public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
            MyMessage newBody = newExchange.getIn().getBody(MyMessage.class);
            if (oldExchange == null) {
                newExchange.getIn().setBody(List.of(newBody));
                return newExchange;
            } else {
                List<?> oldList = oldExchange.getIn().getBody(List.class);
                oldExchange.getIn().setBody(Stream.concat(oldList.stream(), Stream.of(newBody)).toList());
                return oldExchange;
            }
        }
    }
}
package com.btb.jmscamel;

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
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Component
@Slf4j
@RequiredArgsConstructor
public class AggregateMessageRoute extends RouteBuilder {

    private final ProducerTemplate producerTemplate;
    private final AtomicInteger counter = new AtomicInteger();

    @Value("${jc.aggregate.redis.endpoint:127.0.0.1:6379}")
    private String endpoint;

    @Value("${jc.aggregate.timer.period:1000}")
    private int period;

    public void configure() {
        AggregationRepository repository = new RedisAggregationRepository("jms-aggregate", endpoint);
        Random random = new Random(123);

        // Send 3 messages to a queue every 5 seconds
        from(String.format("timer:aggregateTestTimer?period=%d", period)).routeId("generate-route")
                .process(exchange -> {
                    final String id = UUID.randomUUID().toString();
                    IntStream.range(0,random.nextInt(10)).forEach(i -> {
                        MyMessage message = new MyMessage(id, Integer.toString(counter.incrementAndGet()));
                        var newExchange = exchange.copy();
                        newExchange.getIn().setBody(message);
                        producerTemplate.send("direct:marshalToJson", newExchange);
                    });
                });

        from("direct:marshalToJson")
                .marshal().json(JsonLibrary.Jackson)
                .to("jms:queue:A_INCOMING");

        from("jms:queue:A_INCOMING")
                .unmarshal().json(JsonLibrary.Jackson, MyMessage.class)
                .aggregate(simple("${body.id}"), new MyAggregationStrategy())
                .aggregationRepository(repository)
                .completionTimeout(TimeUnit.MILLISECONDS.convert(10, TimeUnit.SECONDS))
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

        from("jms:queue:A_AGGREGATED").routeId("receive-route")
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
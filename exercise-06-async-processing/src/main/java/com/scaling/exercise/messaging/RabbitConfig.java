package com.scaling.exercise.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration — queues, exchanges, and bindings.
 *
 * ARCHITECTURE:
 *
 *   Producer (API) → Exchange → Queue → Consumer (background worker)
 *
 * KEY CONCEPTS:
 *
 * 1. EXCHANGE: Routes messages to queues. We use a direct exchange —
 *    messages with routing key "product.create" go to the product queue.
 *
 * 2. QUEUE: Stores messages until a consumer picks them up. Messages
 *    survive RabbitMQ restarts if the queue is durable.
 *
 * 3. BINDING: Connects an exchange to a queue with a routing key.
 *    Exchange "product-exchange" + routing key "product.create"
 *    → routes to "product-creation-queue"
 *
 * 4. DEAD LETTER QUEUE (DLQ): Where failed messages go after max retries.
 *    Instead of losing the message, we park it in the DLQ for inspection.
 *    An operator can fix the issue and replay the messages.
 *
 * MESSAGE FLOW:
 *
 *   API call → publish to "product-exchange" with key "product.create"
 *            → message lands in "product-creation-queue"
 *            → consumer picks it up and processes it
 *            → on failure: retry up to 3 times
 *            → after 3 failures: message moves to DLQ
 */
@Configuration
public class RabbitConfig {

    // Main queue and exchange
    public static final String PRODUCT_QUEUE = "product-creation-queue";
    public static final String PRODUCT_EXCHANGE = "product-exchange";
    public static final String PRODUCT_ROUTING_KEY = "product.create";

    // Dead letter queue (Part C)
    public static final String DLQ_QUEUE = "product-creation-dlq";
    public static final String DLQ_EXCHANGE = "product-dlx";
    public static final String DLQ_ROUTING_KEY = "product.create.failed";

    // --- Dead Letter Exchange and Queue ---

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLQ_EXCHANGE);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue())
                .to(deadLetterExchange())
                .with(DLQ_ROUTING_KEY);
    }

    // --- Main Exchange and Queue ---

    @Bean
    public DirectExchange productExchange() {
        return new DirectExchange(PRODUCT_EXCHANGE);
    }

    /**
     * Main queue with dead letter configuration.
     *
     * When a message is rejected (nack) or exceeds the retry limit,
     * RabbitMQ automatically routes it to the DLQ via the dead letter
     * exchange. No application code needed — it's a broker-level feature.
     */
    @Bean
    public Queue productQueue() {
        return QueueBuilder.durable(PRODUCT_QUEUE)
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding productBinding() {
        return BindingBuilder.bind(productQueue())
                .to(productExchange())
                .with(PRODUCT_ROUTING_KEY);
    }

    // --- Message Converter ---

    /**
     * JSON message converter — serialize Java objects to JSON in RabbitMQ.
     * Same idea as Jackson serialization for Redis, but for messages.
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }
}

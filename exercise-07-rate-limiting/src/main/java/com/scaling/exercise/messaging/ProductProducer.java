package com.scaling.exercise.messaging;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.net.InetAddress;

/**
 * Publishes product creation messages to RabbitMQ.
 *
 * SYNC vs ASYNC flow:
 *
 *   SYNC (Exercise 05):
 *     POST /api/products
 *       → validate
 *       → INSERT into PostgreSQL
 *       → evict caches
 *       → return 201 with product
 *     Total: 50-200ms (user waits for everything)
 *
 *   ASYNC (Exercise 06):
 *     POST /api/products
 *       → validate
 *       → publish message to RabbitMQ (~1ms)
 *       → return 202 Accepted with requestId
 *     Total: ~5ms (user gets response immediately)
 *
 *     Background (consumer picks up the message):
 *       → INSERT into PostgreSQL
 *       → evict caches
 *     (user doesn't wait for this)
 *
 * The API thread is free almost immediately. Under load, this means
 * the Tomcat thread pool doesn't fill up with slow write operations.
 */
@Service
public class ProductProducer {

    private final RabbitTemplate rabbitTemplate;
    private final String serverId;

    public ProductProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        this.serverId = hostname;
    }

    /**
     * Publish a product creation message to RabbitMQ.
     *
     * This returns in ~1ms regardless of database load, cache state,
     * or how many products are being created simultaneously.
     * The actual work happens later in the consumer.
     */
    public void publishProductCreation(ProductMessage message) {
        rabbitTemplate.convertAndSend(
                RabbitConfig.PRODUCT_EXCHANGE,
                RabbitConfig.PRODUCT_ROUTING_KEY,
                message
        );

        System.out.println("[Producer] Published product creation: " +
                message.getName() + " (requestId: " + message.getRequestId() +
                ") from server: " + serverId);
    }
}

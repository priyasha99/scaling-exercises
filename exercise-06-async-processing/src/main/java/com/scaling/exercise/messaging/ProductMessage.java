package com.scaling.exercise.messaging;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Message payload for async product creation.
 *
 * This is what travels through RabbitMQ — a simple data object
 * containing everything needed to create a product. It's serialized
 * to JSON by Jackson when published, and deserialized back when consumed.
 *
 * WHY A SEPARATE CLASS (not the Product entity)?
 * The Product entity has JPA annotations, a generated ID, and a
 * createdAt timestamp. The message is just the input data. Keeping
 * them separate means the message format doesn't change when we
 * modify the entity.
 */
public class ProductMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String requestId;    // Unique ID for tracking this request
    private String name;
    private String description;
    private BigDecimal price;
    private String category;
    private int stockQuantity;
    private String createdBy;    // Username from JWT
    private long publishedAt;    // Timestamp when message was published
    private int retryCount;      // How many times this has been retried

    public ProductMessage() {}

    public ProductMessage(String requestId, String name, String description,
                          BigDecimal price, String category, int stockQuantity,
                          String createdBy) {
        this.requestId = requestId;
        this.name = name;
        this.description = description;
        this.price = price;
        this.category = category;
        this.stockQuantity = stockQuantity;
        this.createdBy = createdBy;
        this.publishedAt = System.currentTimeMillis();
        this.retryCount = 0;
    }

    // Getters and setters
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public int getStockQuantity() { return stockQuantity; }
    public void setStockQuantity(int stockQuantity) { this.stockQuantity = stockQuantity; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public long getPublishedAt() { return publishedAt; }
    public void setPublishedAt(long publishedAt) { this.publishedAt = publishedAt; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
}

package com.scaling.exercise.config;

import com.scaling.exercise.model.Product;
import com.scaling.exercise.repository.ProductRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seeds the database with 5000 products on startup.
 *
 * IMPORTANT — Race Condition with Shared Database:
 * In Exercise 01/02, each server had its own H2 database, so each
 * server seeded independently with no conflicts. Now all servers
 * share ONE PostgreSQL database.
 *
 * When 3 app servers start simultaneously, they all check
 * productRepository.count() at roughly the same time. It's possible
 * (though unlikely with JVM startup variance) that two servers both
 * see count=0 and both try to seed — resulting in ~10,000 products.
 *
 * For this exercise, that's fine. The count check works well enough
 * because JVM startup times vary by seconds, and the first server
 * to seed takes ~5-10 seconds to insert 5000 rows.
 *
 * In production, you'd solve this with:
 *   - Database migrations (Flyway/Liquibase) — run once, tracked
 *   - A dedicated initialization job (Kubernetes Job)
 *   - Advisory locks: SELECT pg_advisory_lock(1) to serialize
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final ProductRepository productRepository;
    private final Random random = new Random(42); // Fixed seed for reproducibility

    private static final String[] CATEGORIES = {
            "Electronics", "Books", "Clothing", "Home & Kitchen",
            "Sports", "Toys", "Health", "Automotive"
    };

    private static final String[] ADJECTIVES = {
            "Premium", "Essential", "Professional", "Ultimate", "Classic",
            "Advanced", "Deluxe", "Basic", "Super", "Mega", "Ultra",
            "Compact", "Portable", "Wireless", "Smart", "Eco-Friendly"
    };

    private static final String[] NOUNS = {
            "Widget", "Gadget", "Tool", "Device", "Kit", "Set",
            "Pack", "Bundle", "System", "Station", "Hub", "Adapter",
            "Controller", "Monitor", "Sensor", "Tracker", "Guard"
    };

    public DataSeeder(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public void run(String... args) {
        long existingCount = productRepository.count();
        if (existingCount > 0) {
            System.out.println("Database already has " + existingCount +
                    " products (seeded by another server). Skipping.");
            return;
        }

        System.out.println("Seeding database with 5000 products...");

        List<Product> products = new ArrayList<>();

        for (int i = 0; i < 5000; i++) {
            String category = CATEGORIES[random.nextInt(CATEGORIES.length)];
            String adjective = ADJECTIVES[random.nextInt(ADJECTIVES.length)];
            String noun = NOUNS[random.nextInt(NOUNS.length)];
            String name = adjective + " " + noun + " " + (i + 1);
            String description = "A high-quality " + adjective.toLowerCase() + " " +
                    noun.toLowerCase() + " perfect for " + category.toLowerCase() +
                    " enthusiasts. Features durable construction and reliable performance. " +
                    "Model #" + (i + 1) + " in our " + category + " line.";
            BigDecimal price = BigDecimal.valueOf(5 + random.nextDouble() * 495)
                    .setScale(2, BigDecimal.ROUND_HALF_UP);
            int stock = random.nextInt(500);

            products.add(new Product(name, description, price, category, stock));
        }

        productRepository.saveAll(products);
        System.out.println("Seeded " + products.size() + " products into the shared database.");
    }
}

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
 * Enough data to make queries non-trivial, but small enough
 * to fit in H2's memory without issues.
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
        if (productRepository.count() > 0) {
            return; // Already seeded
        }

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
        System.out.println("Seeded " + products.size() + " products into the database.");
    }
}

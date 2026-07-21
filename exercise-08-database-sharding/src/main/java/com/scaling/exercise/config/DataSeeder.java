package com.scaling.exercise.config;

import com.scaling.exercise.model.Product;
import com.scaling.exercise.model.User;
import com.scaling.exercise.repository.ProductRepository;
import com.scaling.exercise.repository.UserRepository;
import com.scaling.exercise.sharding.ShardContext;
import com.scaling.exercise.sharding.ShardingService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Seeds the database with 5000 products on startup.
 *
 * EXERCISE 08 CHANGES:
 * When sharding is enabled, products are distributed across shards
 * based on their category (hash-based). Each shard gets the products
 * whose categories hash to that shard index.
 *
 * Users are seeded to shard-0 only (the default shard). User auth
 * queries (login) don't set a ShardContext, so they naturally route
 * to shard-0 via the default DataSource target.
 *
 * @Order(10) ensures this runs AFTER the schema initializer (@Order(1))
 * so that shard-1 tables exist before we try to insert into them.
 */
@Component
@Order(10)
public class DataSeeder implements CommandLineRunner {

    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ShardingService shardingService;
    private final Random random = new Random(42);

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

    public DataSeeder(ProductRepository productRepository,
                      UserRepository userRepository,
                      PasswordEncoder passwordEncoder,
                      ShardingService shardingService) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.shardingService = shardingService;
    }

    @Override
    public void run(String... args) {
        if (shardingService.isEnabled()) {
            seedSharded();
        } else {
            seedSingle();
        }
    }

    /**
     * Original single-database seeding (when sharding is disabled).
     */
    private void seedSingle() {
        long existingCount = productRepository.count();
        if (existingCount > 0) {
            System.out.println("Database already has " + existingCount +
                    " products (seeded by another server). Skipping.");
            seedUsers();
            return;
        }

        System.out.println("Seeding database with 5000 products...");
        List<Product> products = generateProducts();
        productRepository.saveAll(products);
        System.out.println("Seeded " + products.size() + " products into the shared database.");
        seedUsers();
    }

    /**
     * Shard-aware seeding — distribute products by category hash.
     *
     * 1. Generate all 5000 products
     * 2. Group them by shard (based on category hash)
     * 3. Insert each group into the correct shard
     *
     * This ensures the data distribution matches the routing logic:
     * a query for "Electronics" products hits the same shard where
     * Electronics products were inserted.
     */
    private void seedSharded() {
        // Check if shard-0 already has data
        ShardContext.setCurrentShard("shard-0");
        try {
            if (productRepository.count() > 0) {
                System.out.println("Shard-0 already has data. Skipping seed.");
                seedUsersOnDefaultShard();
                return;
            }
        } finally {
            ShardContext.clear();
        }

        System.out.println("Seeding 5000 products across " +
                shardingService.getShardCount() + " shards...");

        // Generate all products and group by shard
        List<Product> allProducts = generateProducts();
        Map<Integer, List<Product>> productsByShard = new HashMap<>();
        for (int i = 0; i < shardingService.getShardCount(); i++) {
            productsByShard.put(i, new ArrayList<>());
        }

        for (Product p : allProducts) {
            int shard = shardingService.getShardForCategory(p.getCategory());
            productsByShard.get(shard).add(p);
        }

        // Seed each shard
        for (int i = 0; i < shardingService.getShardCount(); i++) {
            List<Product> shardProducts = productsByShard.get(i);
            ShardContext.setCurrentShard(shardingService.getShardId(i));
            try {
                productRepository.saveAll(shardProducts);
                System.out.println("Seeded " + shardProducts.size() +
                        " products to shard-" + i);
            } finally {
                ShardContext.clear();
            }
        }

        // Seed users on default shard (shard-0)
        seedUsersOnDefaultShard();
    }

    /**
     * Seed users on shard-0 (the default shard).
     * Login queries don't set ShardContext, so they route to shard-0.
     */
    private void seedUsersOnDefaultShard() {
        ShardContext.setCurrentShard("shard-0");
        try {
            seedUsers();
        } finally {
            ShardContext.clear();
        }
    }

    private List<Product> generateProducts() {
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
        return products;
    }

    private void seedUsers() {
        if (userRepository.count() > 0) {
            System.out.println("Users already exist. Skipping user seeding.");
            return;
        }

        userRepository.save(new User("admin", passwordEncoder.encode("admin123"), "ADMIN"));
        userRepository.save(new User("user", passwordEncoder.encode("user123"), "USER"));
        userRepository.save(new User("alice", passwordEncoder.encode("alice123"), "USER"));
        userRepository.save(new User("bob", passwordEncoder.encode("bob123"), "USER"));

        System.out.println("Seeded 4 default users (admin, user, alice, bob).");
    }
}

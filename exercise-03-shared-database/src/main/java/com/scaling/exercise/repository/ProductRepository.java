package com.scaling.exercise.repository;

import com.scaling.exercise.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByCategory(String category);

    /**
     * Intentionally uses LIKE with wildcards on both sides.
     * In Exercise 01/02 with H2 in-memory, this was fast enough.
     * With PostgreSQL on disk, this full table scan is MUCH more
     * expensive — it can't use indexes, reads from disk, and
     * under concurrent load becomes a major bottleneck.
     */
    @Query("SELECT p FROM Product p WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Product> searchByNameOrDescription(@Param("keyword") String keyword);
}

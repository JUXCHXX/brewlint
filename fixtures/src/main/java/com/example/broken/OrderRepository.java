package com.example.broken;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * A Spring Data repository, for PERF001 to resolve the fields that point at it.
 *
 * <p>Declared in its own file, and annotated, because the rule needs the project index to know that
 * {@code OrderRepository} is a repository. A field of an unresolvable type produces no finding at
 * all rather than an assumed one, which is why this exists rather than being implied.
 */
@Repository
public interface OrderRepository {

    Order findById(Long id);

    List<Order> findByCustomerId(Long customerId);

    List<Order> findAll();
}

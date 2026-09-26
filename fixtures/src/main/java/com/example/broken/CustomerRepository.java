package com.example.broken;

import org.springframework.stereotype.Repository;

/** The second repository, so PERF001 has more than one to resolve. */
@Repository
public interface CustomerRepository {

    Customer findById(Long id);

    List<Customer> findAll();
}

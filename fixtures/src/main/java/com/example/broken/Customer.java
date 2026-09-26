package com.example.broken;

import java.time.Instant;

/** A customer, for the N+1 fixture. */
public class Customer {

    private Long id;
    private String name;
    private Instant joinedAt;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}

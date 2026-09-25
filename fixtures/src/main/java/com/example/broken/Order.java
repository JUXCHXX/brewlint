package com.example.broken;

/** FIXTURE. A plain record so the other fixtures can refer to an order without pulling in JPA. */
public record Order(long id, String sku, long amountInCents) {
}

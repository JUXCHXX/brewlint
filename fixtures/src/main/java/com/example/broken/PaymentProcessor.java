package com.example.broken;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * FIXTURE. Deliberately broken: transaction problems that no compiler or code review catches.
 *
 * <p>Expected: TX002 on the self-invoked call, TX003 on the two methods that can throw a checked
 * exception without a rollbackFor.
 */
public class PaymentProcessor {

    private final Connection connection;

    public PaymentProcessor(Connection connection) {
        this.connection = connection;
    }

    /** TX002: capture() calls refund() on itself, so the proxy never sees it. */
    public void capture(String orderId, long amount) {
        validate(orderId);
        ledger(orderId, amount);
    }

    @Transactional
    public void validate(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId is required");
        }
    }

    @Transactional
    public void ledger(String orderId, long amount) {
    }

    /** TX002: an explicit this. call is the same problem. */
    public void settle(String orderId) {
        this.ledger(orderId, 0L);
    }

    /** TX003: SQLException is checked, so this commits unless rollbackFor says otherwise. */
    @Transactional
    public void persist(String orderId) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("insert into payments values (?, ?)")) {
            statement.setString(1, orderId);
            statement.executeUpdate();
        }
    }

    /** TX003: a caught checked exception counts too, not only a declared one. */
    @Transactional
    public void refresh(String orderId) {
        try (PreparedStatement statement =
                     connection.prepareStatement("update payments set seen = 1")) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            log(exception);
        }
    }

    /**
     * Correct: the transaction annotation is not the problem here, so Brewlint has to stay quiet.
     * rollbackFor is present.
     */
    @Transactional(rollbackFor = SQLException.class)
    public void archive(String orderId) throws SQLException {
    }

    /** Correct: rollbackForClass covers every checked exception, so it is never missing. */
    @Transactional(rollbackForClass = Exception.class)
    public void purge(String orderId) throws SQLException {
    }

    /** Correct: only unchecked exceptions, which already roll back. */
    @Transactional
    public void cancel(String orderId) throws IllegalStateException {
    }

    /** Correct: the transaction is used properly, from another bean. */
    public void delegate(PaymentProcessor other, String orderId) {
        other.validate(orderId);
    }

    /** Correct: a helper that is not transactional has no advice to bypass. */
    private void log(Exception exception) {
        System.err.println(exception.getMessage());
    }
}

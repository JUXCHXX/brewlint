package com.example.correct;

import jakarta.persistence.Entity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

/**
 * FIXTURE. Deliberately correct across every rule Brewlint ships in Hito 2.
 *
 * <p>This file is the one that matters most. {@code FixturesScanTest} asserts it produces no
 * findings at all, and CI asserts the same through the shaded jar. A linter that only ever reports
 * something is worthless, and the negative half of the job is the half nobody sees in a demo.
 *
 * <p>Note what is here on purpose: field injection in {@code correct} would be a BEAN003, so this
 * class uses constructor injection throughout.
 */
@Service
public class OrderWorkflow {

    private final OrderRepository repository;

    public OrderWorkflow(OrderRepository repository) {
        this.repository = repository;
    }

    /** Correct: public, so CGLIB can subclass it, and called from another bean. */
    @Transactional
    public void place(String orderId) {
        repository.save(orderId);
        validate(orderId);
    }

    /** Correct: a private helper is fine as long as nothing expects advice on it. */
    private void validate(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId is required");
        }
    }

    /** Correct: readOnly is the point, and no checked exception can escape. */
    @Transactional(readOnly = true)
    public String lookup(String orderId) {
        return repository.find(orderId);
    }

    /** Correct: rollbackFor covers the checked exception. */
    @Transactional(rollbackFor = SQLException.class)
    public void importOrders() throws SQLException, IOException {
        repository.save("imported");
    }

    /** Correct: resources are closed. */
    public int countLines(Path path) throws IOException {
        try (InputStream input = new FileInputStream(path.toFile())) {
            return input.readAllBytes().length;
        }
    }

    /** Correct: a Files stream in try-with-resources. */
    public long countRows(Path path) throws IOException {
        try (var lines = Files.lines(path)) {
            return lines.count();
        }
    }

    /** Correct: no transaction annotation on an unproxyable method. */
    private void noAnnotationHere() {
    }
}

/** Correct: a plain service with constructor injection, no field injection. */
@Service
class OrderNotifications {

    private final OrderRepository repository;

    OrderNotifications(OrderRepository repository) {
        this.repository = repository;
    }

    void notifyCustomer(String orderId) {
        repository.find(orderId);
    }
}

/** Correct: a JPA entity with no stereotype, so component scanning leaves it alone. */
@Entity
class Order {
}

/** Correct: a plain component, no persistence annotation. */
@Service
class OrderRepository {

    void save(String orderId) {
    }

    String find(String orderId) {
        return orderId;
    }
}

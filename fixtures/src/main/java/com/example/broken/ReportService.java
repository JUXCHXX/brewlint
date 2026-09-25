package com.example.broken;

import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import jakarta.persistence.Entity;

/**
 * FIXTURE. Deliberately broken: bean wiring problems.
 *
 * <p>Expected: BEAN001 on {@link Report} (an entity that is also a component), BEAN003 on every
 * injected field, and BEAN002 on {@code ReportCache} held by a singleton.
 */
@Service
public class ReportService {

    /** BEAN003: field injection. */
    @Autowired
    private ReportRepository repository;

    /** BEAN002: a prototype bean held by a singleton, so every caller shares one instance. */
    @Autowired
    private ReportCache cache;

    public Report load(String id) {
        return repository.find(id);
    }
}

/** BEAN002: prototype scoped. The value in the field above is resolved once, at startup. */
@Component
@org.springframework.context.annotation.Scope("prototype")
class ReportCache {
}

/** BEAN001: a JPA entity that component scanning also picks up. */
@Entity
@Component
class Report {
}

/** Correct: an ordinary repository, injected through the constructor. */
@Service
class ReportRepository {

    private final AuditLog auditLog;

    public ReportRepository(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    public Report find(String id) {
        return new Report();
    }
}

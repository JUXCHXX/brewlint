package com.example.broken;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FIXTURE. Deliberately broken: every AOP-dependent annotation in one class, each on a method
 * Spring's proxy cannot intercept. AOP001 is expected to flag all five.
 */
@Service
public class OrderService {

    @Transactional
    private void chargeCard() {
    }

    @Transactional
    public static void auditStatic() {
    }

    @Async
    private void sendReceipt() {
    }

    @Cacheable("orders")
    public final Order findById(long id) {
        return null;
    }

    @CacheEvict("orders")
    private final void evict(long id) {
    }

    @Scheduled(cron = "0 0 3 * * *")
    public static void nightlyReconciliation() {
    }

    @Transactional
    public void correct() {
    }

    void packagePrivateIsProxyable() {
    }
}

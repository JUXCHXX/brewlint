package com.example.broken;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FIXTURE. Deliberately correct. Brewlint's CI asserts this file produces no findings, which is the
 * half of the job that matters: a linter that only ever reports something is worthless.
 */
@Service
public class PaymentService {

    @Transactional
    public void capture(String orderId) {
    }

    @Transactional(readOnly = true)
    public Order lookup(String orderId) {
        return null;
    }

    public void noAnnotationAtAll() {
    }
}

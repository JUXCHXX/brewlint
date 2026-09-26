package com.example.broken;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The N+1: one query for the list, then one more for every element of it.
 *
 * <p>PERF001. A page of twenty customers costs twenty-one round trips, and the response time is
 * the sum of all of them rather than the slowest. Nothing in this file looks wrong: a loop, and a
 * call on the thing the loop is over.
 */
@Service
public class CustomerReportService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;

    public CustomerReportService(OrderRepository orderRepository, CustomerRepository customerRepository) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
    }

    /** One query per customer. A page of twenty rows is twenty-one round trips. */
    @Transactional(readOnly = true)
    public String describeEveryCustomerLastOrder(List<Long> customerIds) {
        StringBuilder report = new StringBuilder();
        for (Long customerId : customerIds) {
            // PERF001: the query is inside the loop, so it runs once per iteration.
            Customer customer = customerRepository.findById(customerId);
            List<Order> orders = orderRepository.findAll();
            report.append(customer.getName())
                    .append(": ")
                    .append(orders.size())
                    .append(" orders\n");
        }
        return report.toString();
    }

    /** The same shape written as a stream, which is how it looks in a codebase that uses them. */
    @Transactional(readOnly = true)
    public int countOrdersPerCustomer(List<Long> customerIds) {
        // PERF001: a forEach with a lambda is a loop written as a method call.
        int[] total = {0};
        customerIds.forEach(customerId -> {
            Customer customer = customerRepository.findById(customerId);
            total[0] += orderRepository.findAll().size();
            total[0] += customer.getId() == null ? 0 : 1;
        });
        return total[0];
    }

    /** A while loop, which is rarer and just as expensive. */
    @Transactional(readOnly = true)
    public String describeWithWhile(List<Long> customerIds) {
        StringBuilder report = new StringBuilder();
        int index = 0;
        while (index < customerIds.size()) {
            // PERF001
            Order order = orderRepository.findById(customerIds.get(index));
            report.append(order == null ? "-" : order.sku()).append('\n');
            index++;
        }
        return report.toString();
    }
}

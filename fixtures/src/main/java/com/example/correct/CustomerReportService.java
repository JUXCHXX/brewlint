package com.example.correct;

import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The same reporting job as the broken fixture, written the way it should be.
 *
 * <p>Every loop here either holds no query or is over a collection that was fetched in one go. This
 * file exists to prove PERF001 stays quiet, which is the half of a rule nobody tests and the half
 * that decides whether anybody keeps the rule installed.
 */
@Service
public class CustomerReportService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;

    public CustomerReportService(OrderRepository orderRepository, CustomerRepository customerRepository) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
    }

    /** One query for the customers, one for the orders, and the loop touches neither. */
    @Transactional(readOnly = true)
    public String describeEveryCustomerLastOrder(List<Long> customerIds) {
        List<Customer> customers = customerRepository.findAllById(customerIds);
        List<Order> orders = orderRepository.findAll();

        Map<Long, Customer> byId = customers.stream()
                .collect(Collectors.toMap(Customer::getId, Function.identity()));

        StringBuilder report = new StringBuilder();
        for (Order order : orders) {
            Customer customer = byId.get(order.customerId());
            if (customer != null) {
                report.append(customer.getName()).append(": ").append(order.sku()).append('\n');
            }
        }
        return report.toString();
    }

    /** Counting in memory. A loop over a collection that is already here is not a query. */
    @Transactional(readOnly = true)
    public int countOrders() {
        List<Order> orders = orderRepository.findAll();
        int count = 0;
        for (Order order : orders) {
            if (order.amountInCents() > 0) {
                count++;
            }
        }
        return count;
    }

    /** Loops over collections and strings, which is most loops. */
    public String join(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            out.append(part).append(',');
        }
        return out.toString();
    }

    /** A stream forEach with no query in it. */
    public int countEven(List<Integer> values) {
        int[] even = {0};
        values.forEach(value -> {
            if (value % 2 == 0) {
                even[0]++;
            }
        });
        return even[0];
    }

    /** A repository interface, so the fields above resolve to something the rule recognises. */
    @Repository
    public interface OrderRepository {

        List<Order> findAll();
    }

    /** And a second one. */
    @Repository
    public interface CustomerRepository {

        List<Customer> findAll();

        List<Customer> findAllById(List<Long> ids);
    }

    /** A customer. */
    public static class Customer {

        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    /** An order. */
    public record Order(long id, String sku, long amountInCents, Long customerId) {
    }
}

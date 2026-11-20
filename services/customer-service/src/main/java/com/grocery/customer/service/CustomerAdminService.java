package com.grocery.customer.service;

import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.RowSource;
import com.grocery.common.web.PageResponse;
import com.grocery.customer.dto.CustomerDtos.CustomerProfile;
import com.grocery.customer.dto.CustomerExports;
import com.grocery.customer.entity.Customer;
import com.grocery.customer.mapper.CustomerMapper;
import com.grocery.customer.repository.CustomerRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/** Read-only customer directory for admins (support look-ups). */
@Service
public class CustomerAdminService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final CustomerRepository customers;
    private final ExportQueries exports;

    public CustomerAdminService(CustomerRepository customers, ExportQueries exports) {
        this.customers = customers;
        this.exports = exports;
    }

    /** Matches {@code query} anywhere in the email or full name (case-insensitive); newest first. */
    @Transactional(readOnly = true)
    public PageResponse<CustomerProfile> search(String query, Integer page, Integer size) {
        return Pages.toResponse(customers.search(like(query), Pages.request(page, size, NEWEST_FIRST)),
                CustomerMapper::toView);
    }

    /**
     * {@code GET /api/admin/customers/export}: the search on every page, newest first or by {@code sort}, in keyset
     * batches. No transaction here: each batch has its own.
     */
    public RowSource<CustomerProfile> searchExport(String query, String sort) {
        return exports.jpql(Customer.class, "c", CustomerRepository.ADMIN_SEARCH)
                .param("query", like(query))
                .orderBy(CustomerExports.SORT.resolve(sort))
                .rows(CustomerMapper::toView);
    }

    /** {@code %query%} lower-cased, or null when blank (no filter). */
    private static String like(String query) {
        return query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
    }
}

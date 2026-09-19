package com.turnos.customers;

import com.turnos.tenancy.Tenant;
import com.turnos.tenancy.TenantRepository;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerSummaryQuery customerSummaryQuery;
    private final TenantRepository tenantRepository;

    public CustomerService(
            CustomerRepository customerRepository,
            CustomerSummaryQuery customerSummaryQuery,
            TenantRepository tenantRepository) {
        this.customerRepository = customerRepository;
        this.customerSummaryQuery = customerSummaryQuery;
        this.tenantRepository = tenantRepository;
    }

    public Page<CustomerResponse> search(UUID tenantId, String search, Pageable pageable) {
        return customerRepository.search(tenantId, search, pageable).map(CustomerResponse::from);
    }

    // Same list as search(), plus the per-customer totals the clients table
    // shows. `dormantDays` keeps only those whose last visit is older than
    // that many days - never-visited customers are not "dormidos", they
    // simply have not started, so a null lastVisit is excluded.
    public Page<CustomerSummaryResponse> summary(
            UUID tenantId, String search, Boolean active, Integer dormantDays, Pageable pageable) {
        Tenant tenant = tenantRepository
                .findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        return customerSummaryQuery.find(
                tenantId, search, active, dormantDays, pageable, ZoneId.of(tenant.getTimezone()));
    }

    public CustomerResponse findById(UUID tenantId, UUID id) {
        return CustomerResponse.from(getOwnedOrThrow(tenantId, id));
    }

    public CustomerResponse create(UUID tenantId, CreateCustomerRequest request) {
        requireUniquePhone(tenantId, request.phone(), null);
        Customer customer = new Customer();
        customer.setTenantId(tenantId);
        customer.setPhone(request.phone());
        customer.setName(request.name());
        customer.setInternalNotes(request.internalNotes());
        customer.setActive(true);
        return CustomerResponse.from(customerRepository.save(customer));
    }

    public CustomerResponse update(UUID tenantId, UUID id, UpdateCustomerRequest request) {
        Customer customer = getOwnedOrThrow(tenantId, id);
        if (request.phone() != null) {
            requireUniquePhone(tenantId, request.phone(), customer.getId());
            customer.setPhone(request.phone());
        }
        if (request.name() != null) {
            customer.setName(request.name());
        }
        if (request.internalNotes() != null) {
            customer.setInternalNotes(request.internalNotes());
        }
        if (request.active() != null) {
            customer.setActive(request.active());
        }
        return CustomerResponse.from(customerRepository.save(customer));
    }

    // Soft delete only (spec section 4, rule 21).
    public void deactivate(UUID tenantId, UUID id) {
        Customer customer = getOwnedOrThrow(tenantId, id);
        customer.setActive(false);
        customerRepository.save(customer);
    }

    private void requireUniquePhone(UUID tenantId, String phone, UUID excludingCustomerId) {
        if (phone == null) {
            return;
        }
        customerRepository.findByTenantIdAndPhone(tenantId, phone).ifPresent(existing -> {
            if (!existing.getId().equals(excludingCustomerId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone already in use");
            }
        });
    }

    private Customer getOwnedOrThrow(UUID tenantId, UUID id) {
        Customer customer = customerRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
        if (!customer.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
        }
        return customer;
    }
}

package com.turnos.customers;

import com.turnos.appointments.AppointmentResponse;
import com.turnos.appointments.AppointmentService;
import com.turnos.auth.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;
    private final AppointmentService appointmentService;

    public CustomerController(CustomerService customerService, AppointmentService appointmentService) {
        this.customerService = customerService;
        this.appointmentService = appointmentService;
    }

    @GetMapping
    public Page<CustomerResponse> search(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam(required = false) String search,
            Pageable pageable) {
        return customerService.search(principal.tenantId(), search, pageable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse create(
            @AuthenticationPrincipal AuthenticatedPrincipal principal, @Valid @RequestBody CreateCustomerRequest request) {
        return customerService.create(principal.tenantId(), request);
    }

    @GetMapping("/{id}")
    public CustomerResponse findById(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        return customerService.findById(principal.tenantId(), id);
    }

    @PatchMapping("/{id}")
    public CustomerResponse update(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCustomerRequest request) {
        return customerService.update(principal.tenantId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        customerService.deactivate(principal.tenantId(), id);
    }

    @GetMapping("/{id}/appointments")
    public List<AppointmentResponse> appointments(
            @AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        return appointmentService.findByCustomer(principal.tenantId(), id);
    }
}

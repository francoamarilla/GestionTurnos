package com.turnos.sales;

import com.turnos.appointments.Appointment;
import com.turnos.appointments.AppointmentOrigin;
import com.turnos.appointments.AppointmentRepository;
import com.turnos.appointments.AppointmentResponse;
import com.turnos.appointments.AppointmentStatus;
import com.turnos.customers.CreateCustomerRequest;
import com.turnos.customers.CustomerRepository;
import com.turnos.customers.CustomerResponse;
import com.turnos.customers.CustomerService;
import com.turnos.professionals.Professional;
import com.turnos.professionals.ProfessionalRepository;
import com.turnos.professionals.ProfessionalServiceAssignmentRepository;
import com.turnos.services.ServiceRepository;
import com.turnos.tenancy.Branch;
import com.turnos.tenancy.BranchRepository;
import com.turnos.tenancy.Tenant;
import com.turnos.tenancy.TenantRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SaleService {

    private final AppointmentRepository appointmentRepository;
    private final ProfessionalRepository professionalRepository;
    private final ProfessionalServiceAssignmentRepository professionalServiceAssignmentRepository;
    private final ServiceRepository serviceRepository;
    private final CustomerRepository customerRepository;
    private final CustomerService customerService;
    private final BranchRepository branchRepository;
    private final TenantRepository tenantRepository;

    public SaleService(
            AppointmentRepository appointmentRepository,
            ProfessionalRepository professionalRepository,
            ProfessionalServiceAssignmentRepository professionalServiceAssignmentRepository,
            ServiceRepository serviceRepository,
            CustomerRepository customerRepository,
            CustomerService customerService,
            BranchRepository branchRepository,
            TenantRepository tenantRepository) {
        this.appointmentRepository = appointmentRepository;
        this.professionalRepository = professionalRepository;
        this.professionalServiceAssignmentRepository = professionalServiceAssignmentRepository;
        this.serviceRepository = serviceRepository;
        this.customerRepository = customerRepository;
        this.customerService = customerService;
        this.branchRepository = branchRepository;
        this.tenantRepository = tenantRepository;
    }

    // "Otro" has no service to take a duration from; it still occupies the
    // professional (overlap constraint), so it gets a short fixed slot.
    // ponytail: fixed 15 min, add a duration field if "Otro" turns out to be
    // long work (a special color) more than a product.
    static final int OFF_CATALOG_MINUTES = 15;

    // Doesn't reuse AvailabilityService's grid-based slot check (unlike
    // regular booking): a walk-in is recorded at whatever moment it actually
    // happened, not constrained to an artificial slot grid. The non-overlap
    // rule still applies "sin excepciones" (spec section 7) - enforced the
    // same way as regular creation, by catching the DB exclusion constraint.
    @Transactional
    public AppointmentResponse create(UUID tenantId, UUID adminId, CreateSaleRequest request) {
        Tenant tenant = getTenant(tenantId);
        ZoneId zone = ZoneId.of(tenant.getTimezone());

        Professional professional = professionalRepository
                .findById(request.professionalId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Professional not found"));
        Branch branch = branchRepository
                .findById(professional.getBranchId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
        if (!branch.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Professional not found");
        }
        if (!professional.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Professional is not active");
        }

        String description = null;
        int durationMinutes;
        if (request.serviceId() == null) {
            if (request.description() == null || request.description().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "A sale without a service needs a description");
            }
            description = request.description().strip();
            durationMinutes = OFF_CATALOG_MINUTES;
        } else {
            durationMinutes = catalogServiceMinutes(tenantId, request);
        }

        UUID customerId = resolveCustomer(
                tenantId, request.customerId(), request.customerName(), request.customerPhone());

        OffsetDateTime startTime = request.startTime() != null ? request.startTime() : OffsetDateTime.now(zone);
        Instant startInstant = startTime.toInstant();

        Appointment appointment = new Appointment();
        appointment.setBranchId(professional.getBranchId());
        appointment.setProfessionalId(request.professionalId());
        appointment.setServiceId(request.serviceId());
        appointment.setDescription(description);
        appointment.setCustomerId(customerId);
        appointment.setStartTime(startInstant);
        appointment.setEndTime(startInstant.plusSeconds(durationMinutes * 60L));
        appointment.setDurationMinutes(durationMinutes);
        appointment.setPrice(request.price());
        appointment.setStatus(AppointmentStatus.COMPLETED);
        appointment.setOrigin(AppointmentOrigin.DIRECT_SALE);
        appointment.setPaymentMethod(request.paymentMethod());
        appointment.setCompletedBy(adminId);
        appointment.setCompletedAt(Instant.now());

        try {
            appointment = appointmentRepository.saveAndFlush(appointment);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "That time overlaps an existing appointment for this professional", e);
        }

        return AppointmentResponse.from(appointment, zone);
    }

    public List<AppointmentResponse> search(UUID tenantId, LocalDate from, LocalDate to, UUID professionalId) {
        Tenant tenant = getTenant(tenantId);
        ZoneId zone = ZoneId.of(tenant.getTimezone());
        Branch branch = getBranch(tenantId);

        Instant fromInstant = from.atStartOfDay(zone).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(zone).toInstant();

        return appointmentRepository
                .search(branch.getId(), fromInstant, toInstant, professionalId, AppointmentStatus.COMPLETED).stream()
                .map(a -> AppointmentResponse.from(a, zone))
                .toList();
    }

    // Validates the catalog service and returns its duration.
    private int catalogServiceMinutes(UUID tenantId, CreateSaleRequest request) {
        var service = serviceRepository
                .findById(request.serviceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found"));
        if (!service.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found");
        }
        if (!service.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Service is not active");
        }

        boolean offersService = professionalServiceAssignmentRepository
                .findByProfessionalId(request.professionalId()).stream()
                .anyMatch(a -> a.getServiceId().equals(request.serviceId()));
        if (!offersService) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Professional does not offer this service");
        }
        return service.getDurationMinutes();
    }

    // A sale is any COMPLETED appointment - a direct sale or a charged
    // booking alike, both show up in Ventas.
    @Transactional
    public AppointmentResponse update(UUID tenantId, UUID id, UpdateSaleRequest request) {
        ZoneId zone = ZoneId.of(getTenant(tenantId).getTimezone());
        Appointment sale = getSaleOrThrow(tenantId, id);

        // Keeping the customer it already has is fine even if they were
        // deactivated since; only a newly picked one must be active.
        UUID customerId = request.customerId() != null && request.customerId().equals(sale.getCustomerId())
                ? sale.getCustomerId()
                : resolveCustomer(tenantId, request.customerId(), request.customerName(), request.customerPhone());

        sale.setPrice(request.price());
        sale.setPaymentMethod(request.paymentMethod());
        sale.setCustomerId(customerId);
        return AppointmentResponse.from(appointmentRepository.save(sale), zone);
    }

    // COMPLETED -> VOIDED: drops out of every total (they all count
    // COMPLETED) and frees the professional's time (V13).
    @Transactional
    public AppointmentResponse voidSale(UUID tenantId, UUID adminId, UUID id, VoidSaleRequest request) {
        ZoneId zone = ZoneId.of(getTenant(tenantId).getTimezone());
        Appointment sale = getSaleOrThrow(tenantId, id);

        String reason = request == null || request.reason() == null ? null : request.reason().strip();
        sale.setStatus(AppointmentStatus.VOIDED);
        sale.setVoidReason(reason == null || reason.isEmpty() ? null : reason);
        sale.setVoidedBy(adminId);
        sale.setVoidedAt(Instant.now());
        return AppointmentResponse.from(appointmentRepository.save(sale), zone);
    }

    private Appointment getSaleOrThrow(UUID tenantId, UUID id) {
        Appointment sale = appointmentRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sale not found"));
        if (!sale.getBranchId().equals(getBranch(tenantId).getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sale not found");
        }
        // Voided, or never charged: nothing to correct.
        if (sale.getStatus() != AppointmentStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a completed sale can be edited or voided");
        }
        return sale;
    }

    private UUID resolveCustomer(UUID tenantId, UUID customerId, String customerName, String customerPhone) {
        if (customerId != null) {
            var customer = customerRepository
                    .findById(customerId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
            if (!customer.getTenantId().equals(tenantId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
            }
            if (!customer.isActive()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer is not active");
            }
            return customer.getId();
        }
        if (customerName != null || customerPhone != null) {
            CustomerResponse created =
                    customerService.create(tenantId, new CreateCustomerRequest(customerPhone, customerName, null));
            return created.id();
        }
        return null;
    }

    private Tenant getTenant(UUID tenantId) {
        return tenantRepository
                .findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
    }

    private Branch getBranch(UUID tenantId) {
        return branchRepository
                .findByTenantId(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
    }
}

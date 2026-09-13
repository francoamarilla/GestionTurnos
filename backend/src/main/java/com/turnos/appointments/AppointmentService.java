package com.turnos.appointments;

import com.turnos.availability.AvailabilityService;
import com.turnos.customers.CustomerRepository;
import com.turnos.professionals.Professional;
import com.turnos.professionals.ProfessionalRepository;
import com.turnos.services.ServiceRepository;
import com.turnos.tenancy.Branch;
import com.turnos.tenancy.BranchRepository;
import com.turnos.tenancy.Tenant;
import com.turnos.tenancy.TenantRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final AvailabilityService availabilityService;
    private final ServiceRepository serviceRepository;
    private final CustomerRepository customerRepository;
    private final ProfessionalRepository professionalRepository;
    private final BranchRepository branchRepository;
    private final TenantRepository tenantRepository;

    public AppointmentService(
            AppointmentRepository appointmentRepository,
            AvailabilityService availabilityService,
            ServiceRepository serviceRepository,
            CustomerRepository customerRepository,
            ProfessionalRepository professionalRepository,
            BranchRepository branchRepository,
            TenantRepository tenantRepository) {
        this.appointmentRepository = appointmentRepository;
        this.availabilityService = availabilityService;
        this.serviceRepository = serviceRepository;
        this.customerRepository = customerRepository;
        this.professionalRepository = professionalRepository;
        this.branchRepository = branchRepository;
        this.tenantRepository = tenantRepository;
    }

    // Atomic (spec section 4, rule 8): the availability check below is a
    // friendly pre-flight, but the real guarantee against a concurrent
    // double-booking is the DB exclusion constraint (V7) - caught here and
    // turned into the specific 409 the spec requires, not a generic error.
    @Transactional
    public AppointmentResponse create(UUID tenantId, CreateAppointmentRequest request) {
        ZoneId zone = ZoneId.of(getTenant(tenantId).getTimezone());

        Professional professional = professionalRepository
                .findById(request.professionalId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Professional not found"));

        // Also validates tenant/branch ownership, active professional/service,
        // and that the professional actually offers this service - reusing
        // the exact same checks GET /api/availability already performs.
        var freeSlots = availabilityService.getAvailability(
                tenantId, request.professionalId(), request.serviceId(), request.startTime().toLocalDate());
        boolean slotIsFree = freeSlots.stream().anyMatch(slot -> slot.isEqual(request.startTime()));
        if (!slotIsFree) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That time is no longer available");
        }

        var service = serviceRepository
                .findById(request.serviceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found"));

        if (request.customerId() != null) {
            var customer = customerRepository
                    .findById(request.customerId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
            if (!customer.getTenantId().equals(tenantId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
            }
            if (!customer.isActive()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer is not active");
            }
        }

        Instant startInstant = request.startTime().toInstant();
        int durationMinutes = service.getDurationMinutes();

        Appointment appointment = new Appointment();
        appointment.setBranchId(professional.getBranchId());
        appointment.setProfessionalId(request.professionalId());
        appointment.setServiceId(request.serviceId());
        appointment.setCustomerId(request.customerId());
        appointment.setStartTime(startInstant);
        appointment.setEndTime(startInstant.plusSeconds(durationMinutes * 60L));
        appointment.setDurationMinutes(durationMinutes);
        appointment.setPrice(service.getPrice());
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        appointment.setOrigin(AppointmentOrigin.DASHBOARD);

        try {
            appointment = appointmentRepository.saveAndFlush(appointment);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That time is no longer available", e);
        }

        return AppointmentResponse.from(appointment, zone);
    }

    public List<AppointmentResponse> search(
            UUID tenantId, LocalDate from, LocalDate to, UUID professionalId, AppointmentStatus status) {
        Tenant tenant = getTenant(tenantId);
        ZoneId zone = ZoneId.of(tenant.getTimezone());
        Branch branch = getBranch(tenantId);

        Instant fromInstant = from.atStartOfDay(zone).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(zone).toInstant();

        return appointmentRepository.search(branch.getId(), fromInstant, toInstant, professionalId, status).stream()
                .map(a -> AppointmentResponse.from(a, zone))
                .toList();
    }

    public AppointmentResponse findById(UUID tenantId, UUID id) {
        ZoneId zone = ZoneId.of(getTenant(tenantId).getTimezone());
        return AppointmentResponse.from(getOwnedOrThrow(tenantId, id), zone);
    }

    // Terminal states (COMPLETED, CANCELLED, NO_SHOW) never transition again,
    // and CONFIRMED is the only valid starting point (spec section 3.3) - any
    // other attempt is a 409, not a 400, since it's a state-dependent
    // conflict rather than malformed input.
    @Transactional
    public AppointmentResponse updateStatus(UUID tenantId, UUID adminId, UUID id, AppointmentStatus newStatus) {
        Tenant tenant = getTenant(tenantId);
        ZoneId zone = ZoneId.of(tenant.getTimezone());
        Appointment appointment = getOwnedOrThrow(tenantId, id);

        boolean validTarget = newStatus == AppointmentStatus.COMPLETED
                || newStatus == AppointmentStatus.CANCELLED
                || newStatus == AppointmentStatus.NO_SHOW;
        if (appointment.getStatus() != AppointmentStatus.CONFIRMED || !validTarget) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid status transition");
        }

        appointment.setStatus(newStatus);
        if (newStatus == AppointmentStatus.COMPLETED) {
            appointment.setCompletedBy(adminId);
            appointment.setCompletedAt(Instant.now());
        } else if (newStatus == AppointmentStatus.NO_SHOW) {
            appointment.setNoShowMarkedBy(adminId);
        }

        return AppointmentResponse.from(appointmentRepository.save(appointment), zone);
    }

    private Appointment getOwnedOrThrow(UUID tenantId, UUID id) {
        Appointment appointment = appointmentRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found"));
        Branch branch = branchRepository
                .findById(appointment.getBranchId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found"));
        if (!branch.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found");
        }
        return appointment;
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

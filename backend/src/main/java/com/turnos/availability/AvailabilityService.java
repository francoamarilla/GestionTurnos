package com.turnos.availability;

import com.turnos.appointments.Appointment;
import com.turnos.appointments.AppointmentRepository;
import com.turnos.appointments.AppointmentStatus;
import com.turnos.professionals.Professional;
import com.turnos.professionals.ProfessionalRepository;
import com.turnos.professionals.ProfessionalScheduleRepository;
import com.turnos.professionals.ProfessionalServiceAssignmentRepository;
import com.turnos.services.ServiceRepository;
import com.turnos.tenancy.Branch;
import com.turnos.tenancy.BranchClosureRepository;
import com.turnos.tenancy.BranchRepository;
import com.turnos.tenancy.BranchSchedule;
import com.turnos.tenancy.BranchScheduleRepository;
import com.turnos.tenancy.Tenant;
import com.turnos.tenancy.TenantRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

// Availability = professional's weekly hours ∩ branch's weekly hours, minus
// branch closures for that date, minus this professional's existing
// non-cancelled appointments that day (spec section 6/19). Computed in the
// tenant's fixed timezone, never the server's.
//
// Slot granularity: the spec describes "franjas horarias concretas" but
// doesn't define a grid, so slots start every serviceDuration minutes,
// back-to-back from the effective opening time - the simplest rule that
// doesn't invent a separate, unspecified "slot size" concept.
@Service
public class AvailabilityService {

    private final TenantRepository tenantRepository;
    private final BranchRepository branchRepository;
    private final BranchScheduleRepository branchScheduleRepository;
    private final BranchClosureRepository branchClosureRepository;
    private final ProfessionalRepository professionalRepository;
    private final ProfessionalScheduleRepository professionalScheduleRepository;
    private final ProfessionalServiceAssignmentRepository professionalServiceAssignmentRepository;
    private final ServiceRepository serviceRepository;
    private final AppointmentRepository appointmentRepository;

    public AvailabilityService(
            TenantRepository tenantRepository,
            BranchRepository branchRepository,
            BranchScheduleRepository branchScheduleRepository,
            BranchClosureRepository branchClosureRepository,
            ProfessionalRepository professionalRepository,
            ProfessionalScheduleRepository professionalScheduleRepository,
            ProfessionalServiceAssignmentRepository professionalServiceAssignmentRepository,
            ServiceRepository serviceRepository,
            AppointmentRepository appointmentRepository) {
        this.tenantRepository = tenantRepository;
        this.branchRepository = branchRepository;
        this.branchScheduleRepository = branchScheduleRepository;
        this.branchClosureRepository = branchClosureRepository;
        this.professionalRepository = professionalRepository;
        this.professionalScheduleRepository = professionalScheduleRepository;
        this.professionalServiceAssignmentRepository = professionalServiceAssignmentRepository;
        this.serviceRepository = serviceRepository;
        this.appointmentRepository = appointmentRepository;
    }

    public List<OffsetDateTime> getAvailability(UUID tenantId, UUID professionalId, UUID serviceId, LocalDate date) {
        Tenant tenant = tenantRepository
                .findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        ZoneId zone = ZoneId.of(tenant.getTimezone());

        Professional professional = professionalRepository
                .findById(professionalId)
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

        var service = serviceRepository
                .findById(serviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found"));
        if (!service.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found");
        }
        if (!service.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Service is not active");
        }

        boolean offersService = professionalServiceAssignmentRepository.findByProfessionalId(professionalId).stream()
                .anyMatch(assignment -> assignment.getServiceId().equals(serviceId));
        if (!offersService) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Professional does not offer this service");
        }

        boolean branchClosedThatDay = branchClosureRepository.findByBranchId(branch.getId()).stream()
                .anyMatch(closure -> closure.getDate().equals(date));
        if (branchClosedThatDay) {
            return List.of();
        }

        DayOfWeek dayOfWeek = date.getDayOfWeek();

        BranchSchedule branchDay = branchScheduleRepository.findByBranchId(branch.getId()).stream()
                .filter(s -> s.getDayOfWeek() == dayOfWeek)
                .findFirst()
                .orElse(null);
        if (branchDay == null || branchDay.isClosed() || branchDay.getOpenTime() == null || branchDay.getCloseTime() == null) {
            return List.of();
        }

        var professionalDay = professionalScheduleRepository.findByProfessionalId(professionalId).stream()
                .filter(s -> s.getDayOfWeek() == dayOfWeek)
                .findFirst()
                .orElse(null);
        if (professionalDay == null
                || professionalDay.isClosed()
                || professionalDay.getOpenTime() == null
                || professionalDay.getCloseTime() == null) {
            return List.of();
        }

        LocalTime openTime = laterOf(branchDay.getOpenTime(), professionalDay.getOpenTime());
        LocalTime closeTime = earlierOf(branchDay.getCloseTime(), professionalDay.getCloseTime());

        int openMinutes = openTime.getHour() * 60 + openTime.getMinute();
        int closeMinutes = closeTime.getHour() * 60 + closeTime.getMinute();
        int durationMinutes = service.getDurationMinutes();

        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<Appointment> sameDayAppointments =
                appointmentRepository.findByProfessionalIdAndStartTimeLessThanAndEndTimeGreaterThan(
                        professionalId, dayEnd, dayStart);
        // Same rule as the appointment_no_overlap constraint (V13).
        sameDayAppointments.removeIf(a -> a.getStatus() == AppointmentStatus.CANCELLED
                || a.getStatus() == AppointmentStatus.VOIDED);

        List<OffsetDateTime> slots = new ArrayList<>();
        for (int cursor = openMinutes; cursor + durationMinutes <= closeMinutes; cursor += durationMinutes) {
            LocalTime slotTime = LocalTime.of(cursor / 60, cursor % 60);
            ZonedDateTime slotStartZoned = date.atTime(slotTime).atZone(zone);
            Instant slotStart = slotStartZoned.toInstant();
            Instant slotEnd = slotStart.plusSeconds(durationMinutes * 60L);

            boolean conflict = sameDayAppointments.stream()
                    .anyMatch(a -> a.getStartTime().isBefore(slotEnd) && slotStart.isBefore(a.getEndTime()));
            if (!conflict) {
                slots.add(slotStartZoned.toOffsetDateTime());
            }
        }
        return slots;
    }

    private LocalTime laterOf(LocalTime a, LocalTime b) {
        return a.isAfter(b) ? a : b;
    }

    private LocalTime earlierOf(LocalTime a, LocalTime b) {
        return a.isBefore(b) ? a : b;
    }
}

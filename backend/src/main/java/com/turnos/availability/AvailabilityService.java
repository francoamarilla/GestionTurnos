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

        // The professional works when both are open, range by range: with a
        // split day (#7) that can be two windows, and an empty list means
        // nobody works that day.
        List<int[]> windows = workingWindows(
                branchScheduleRepository.findByBranchId(branch.getId()).stream()
                        .filter(s -> s.getDayOfWeek() == dayOfWeek)
                        .map(s -> new int[] {minutesOf(s.getOpenTime()), minutesOf(s.getCloseTime())})
                        .toList(),
                professionalScheduleRepository.findByProfessionalId(professionalId).stream()
                        .filter(s -> s.getDayOfWeek() == dayOfWeek)
                        .map(s -> new int[] {minutesOf(s.getOpenTime()), minutesOf(s.getCloseTime())})
                        .toList());
        if (windows.isEmpty()) {
            return List.of();
        }

        int durationMinutes = service.getDurationMinutes();

        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<Appointment> sameDayAppointments =
                appointmentRepository.findByProfessionalIdAndStartTimeLessThanAndEndTimeGreaterThan(
                        professionalId, dayEnd, dayStart);
        // Same rule as the appointment_no_overlap constraint (V13).
        sameDayAppointments.removeIf(a -> a.getStatus() == AppointmentStatus.CANCELLED
                || a.getStatus() == AppointmentStatus.VOIDED);

        // Every service starts on the tenant's own grid (#6), so a 20-minute
        // service on a 30-minute grid offers 9:00, 9:30, ... not 9:00, 9:20.
        // Older tenants have no value yet: fall back to the old behaviour.
        int step = tenant.getSlotIntervalMinutes() != null ? tenant.getSlotIntervalMinutes() : durationMinutes;

        List<OffsetDateTime> slots = new ArrayList<>();
        // The grid restarts on each window: an afternoon that opens at 16:00
        // offers 16:00, not whatever the morning's rhythm landed on.
        for (int[] window : windows) {
            for (int cursor = window[0]; cursor + durationMinutes <= window[1]; cursor += step) {
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
        }
        return slots;
    }

    // What a customer may book (bot, AI agent): the same slots minus the ones
    // already in the past. The panel keeps getAvailability() unfiltered.
    public List<OffsetDateTime> getBookableAvailability(
            UUID tenantId, UUID professionalId, UUID serviceId, LocalDate date) {
        Instant now = Instant.now();
        return getAvailability(tenantId, professionalId, serviceId, date).stream()
                .filter(slot -> slot.toInstant().isAfter(now))
                .toList();
    }

    // Every overlap of an open range with a worked range, in minutes from
    // midnight. Public-ish so StatisticsService can count the same hours.
    public static List<int[]> workingWindows(List<int[]> branchRanges, List<int[]> professionalRanges) {
        List<int[]> windows = new ArrayList<>();
        for (int[] open : branchRanges) {
            for (int[] worked : professionalRanges) {
                int start = Math.max(open[0], worked[0]);
                int end = Math.min(open[1], worked[1]);
                if (start < end) {
                    windows.add(new int[] {start, end});
                }
            }
        }
        windows.sort((a, b) -> Integer.compare(a[0], b[0]));
        return windows;
    }

    private static int minutesOf(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }
}

package com.turnos.availability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.turnos.appointments.Appointment;
import com.turnos.appointments.AppointmentRepository;
import com.turnos.appointments.AppointmentStatus;
import com.turnos.professionals.Professional;
import com.turnos.professionals.ProfessionalRepository;
import com.turnos.professionals.ProfessionalSchedule;
import com.turnos.professionals.ProfessionalScheduleRepository;
import com.turnos.professionals.ProfessionalServiceAssignment;
import com.turnos.professionals.ProfessionalServiceAssignmentRepository;
import com.turnos.services.Service;
import com.turnos.services.ServiceRepository;
import com.turnos.tenancy.Branch;
import com.turnos.tenancy.BranchClosure;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

// The heaviest-tested piece of the project (per the build plan): everything
// that decides whether a slot is offered to a customer routes through here.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AvailabilityServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private BranchRepository branchRepository;

    @Mock
    private BranchScheduleRepository branchScheduleRepository;

    @Mock
    private BranchClosureRepository branchClosureRepository;

    @Mock
    private ProfessionalRepository professionalRepository;

    @Mock
    private ProfessionalScheduleRepository professionalScheduleRepository;

    @Mock
    private ProfessionalServiceAssignmentRepository professionalServiceAssignmentRepository;

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private AppointmentRepository appointmentRepository;

    private AvailabilityService availabilityService;
    private Tenant tenant;
    private Service service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID branchId = UUID.randomUUID();
    private final UUID professionalId = UUID.randomUUID();
    private final UUID serviceId = UUID.randomUUID();
    private final LocalDate monday = LocalDate.of(2026, 6, 1);
    private final ZoneId zone = ZoneId.of("America/Argentina/Buenos_Aires");

    @BeforeEach
    void setUp() {
        availabilityService = new AvailabilityService(
                tenantRepository,
                branchRepository,
                branchScheduleRepository,
                branchClosureRepository,
                professionalRepository,
                professionalScheduleRepository,
                professionalServiceAssignmentRepository,
                serviceRepository,
                appointmentRepository);

        tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setTimezone(zone.getId());
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        Professional professional = new Professional();
        professional.setId(professionalId);
        professional.setBranchId(branchId);
        professional.setActive(true);
        when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));

        Branch branch = new Branch();
        branch.setId(branchId);
        branch.setTenantId(tenantId);
        when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));

        service = new Service();
        service.setId(serviceId);
        service.setTenantId(tenantId);
        service.setActive(true);
        service.setDurationMinutes(30);
        when(serviceRepository.findById(serviceId)).thenReturn(Optional.of(service));

        ProfessionalServiceAssignment assignment = new ProfessionalServiceAssignment();
        assignment.setProfessionalId(professionalId);
        assignment.setServiceId(serviceId);
        when(professionalServiceAssignmentRepository.findByProfessionalId(professionalId))
                .thenReturn(List.of(assignment));

        when(branchClosureRepository.findByBranchId(branchId)).thenReturn(List.of());
        when(branchScheduleRepository.findByBranchId(branchId))
                .thenReturn(List.of(branchSchedule(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(18, 0))));
        when(professionalScheduleRepository.findByProfessionalId(professionalId))
                .thenReturn(List.of(professionalSchedule(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(18, 0))));
        when(appointmentRepository.findByProfessionalIdAndStartTimeLessThanAndEndTimeGreaterThan(any(), any(), any()))
                .thenReturn(new ArrayList<>());
    }

    @Test
    void bookableAvailabilityDropsPastSlots() {
        LocalDate nextMonday = LocalDate.now(zone).with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY));

        assertTrue(availabilityService.getBookableAvailability(tenantId, professionalId, serviceId, monday).isEmpty());
        assertEquals(
                18, availabilityService.getBookableAvailability(tenantId, professionalId, serviceId, nextMonday).size());
    }

    @Test
    void generatesBackToBackSlotsWithinOpenHours() {
        List<OffsetDateTime> slots = availabilityService.getAvailability(tenantId, professionalId, serviceId, monday);

        assertEquals(18, slots.size());
        assertEquals(LocalTime.of(9, 0), slots.get(0).toLocalTime());
        assertEquals(LocalTime.of(17, 30), slots.get(slots.size() - 1).toLocalTime());
    }

    // ENDPOINTS-PENDIENTES.md #6: every service starts on the tenant's grid.
    @Test
    void startsSlotsOnTheTenantsGridNotOnTheServiceDuration() {
        service.setDurationMinutes(20);
        tenant.setSlotIntervalMinutes(30);

        List<OffsetDateTime> slots = availabilityService.getAvailability(tenantId, professionalId, serviceId, monday);

        assertEquals(LocalTime.of(9, 0), slots.get(0).toLocalTime());
        assertEquals(LocalTime.of(9, 30), slots.get(1).toLocalTime());
        // 9:00 to 18:00, one start every 30 minutes, the last one at 17:30.
        assertEquals(18, slots.size());
        assertEquals(LocalTime.of(17, 30), slots.get(slots.size() - 1).toLocalTime());
    }

    @Test
    void aFinerGridOffersMoreStartsForTheSameService() {
        tenant.setSlotIntervalMinutes(15);

        List<OffsetDateTime> slots = availabilityService.getAvailability(tenantId, professionalId, serviceId, monday);

        assertEquals(LocalTime.of(9, 15), slots.get(1).toLocalTime());
        // 30-minute service: the last one that still fits starts at 17:30.
        assertEquals(LocalTime.of(17, 30), slots.get(slots.size() - 1).toLocalTime());
        assertEquals(35, slots.size());
    }

    @Test
    void excludesSlotOverlappingAnExistingAppointment() {
        Instant existingStart = monday.atTime(10, 0).atZone(zone).toInstant();
        Appointment existing = new Appointment();
        existing.setProfessionalId(professionalId);
        existing.setStartTime(existingStart);
        existing.setEndTime(existingStart.plusSeconds(30 * 60));
        existing.setStatus(AppointmentStatus.CONFIRMED);
        when(appointmentRepository.findByProfessionalIdAndStartTimeLessThanAndEndTimeGreaterThan(any(), any(), any()))
                .thenReturn(new ArrayList<>(List.of(existing)));

        List<OffsetDateTime> slots = availabilityService.getAvailability(tenantId, professionalId, serviceId, monday);

        assertEquals(17, slots.size());
        assertTrue(slots.stream().noneMatch(s -> s.toLocalTime().equals(LocalTime.of(10, 0))));
    }

    @Test
    void cancelledAppointmentDoesNotBlockTheSlot() {
        Instant existingStart = monday.atTime(10, 0).atZone(zone).toInstant();
        Appointment cancelled = new Appointment();
        cancelled.setProfessionalId(professionalId);
        cancelled.setStartTime(existingStart);
        cancelled.setEndTime(existingStart.plusSeconds(30 * 60));
        cancelled.setStatus(AppointmentStatus.CANCELLED);
        when(appointmentRepository.findByProfessionalIdAndStartTimeLessThanAndEndTimeGreaterThan(any(), any(), any()))
                .thenReturn(new ArrayList<>(List.of(cancelled)));

        List<OffsetDateTime> slots = availabilityService.getAvailability(tenantId, professionalId, serviceId, monday);

        assertEquals(18, slots.size());
    }

    @Test
    void branchClosureBlocksTheWholeDay() {
        BranchClosure closure = new BranchClosure();
        closure.setBranchId(branchId);
        closure.setDate(monday);
        when(branchClosureRepository.findByBranchId(branchId)).thenReturn(List.of(closure));

        List<OffsetDateTime> slots = availabilityService.getAvailability(tenantId, professionalId, serviceId, monday);

        assertTrue(slots.isEmpty());
    }

    // A day the branch doesn't open is a day with no ranges at all (V15).
    @Test
    void aDayWithNoBranchRangesBlocksTheWholeDay() {
        when(branchScheduleRepository.findByBranchId(branchId)).thenReturn(List.of());

        List<OffsetDateTime> slots = availabilityService.getAvailability(tenantId, professionalId, serviceId, monday);

        assertTrue(slots.isEmpty());
    }

    // ENDPOINTS-PENDIENTES.md #7: a split day offers nothing at siesta time.
    @Test
    void aSplitDayLeavesTheMiddayGapOutOfTheOfferedSlots() {
        when(branchScheduleRepository.findByBranchId(branchId))
                .thenReturn(List.of(
                        branchSchedule(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(13, 0)),
                        branchSchedule(DayOfWeek.MONDAY, LocalTime.of(16, 0), LocalTime.of(20, 0))));
        when(professionalScheduleRepository.findByProfessionalId(professionalId))
                .thenReturn(List.of(
                        professionalSchedule(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(13, 0)),
                        professionalSchedule(DayOfWeek.MONDAY, LocalTime.of(16, 0), LocalTime.of(20, 0))));

        List<LocalTime> times = availabilityService.getAvailability(tenantId, professionalId, serviceId, monday).stream()
                .map(OffsetDateTime::toLocalTime)
                .toList();

        // 30-minute service: 9:00-12:30 and 16:00-19:30, nothing between.
        assertEquals(LocalTime.of(9, 0), times.get(0));
        assertEquals(LocalTime.of(12, 30), times.get(7));
        assertEquals(LocalTime.of(16, 0), times.get(8));
        assertEquals(LocalTime.of(19, 30), times.get(times.size() - 1));
        assertEquals(16, times.size());
    }

    // The professional only does afternoons: the morning range drops out.
    @Test
    void onlyTheOverlapOfBranchAndProfessionalRangesIsOffered() {
        when(branchScheduleRepository.findByBranchId(branchId))
                .thenReturn(List.of(
                        branchSchedule(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(13, 0)),
                        branchSchedule(DayOfWeek.MONDAY, LocalTime.of(16, 0), LocalTime.of(20, 0))));
        when(professionalScheduleRepository.findByProfessionalId(professionalId))
                .thenReturn(List.of(professionalSchedule(DayOfWeek.MONDAY, LocalTime.of(17, 0), LocalTime.of(20, 0))));

        List<LocalTime> times = availabilityService.getAvailability(tenantId, professionalId, serviceId, monday).stream()
                .map(OffsetDateTime::toLocalTime)
                .toList();

        assertEquals(LocalTime.of(17, 0), times.get(0));
        assertEquals(LocalTime.of(19, 30), times.get(times.size() - 1));
        assertEquals(6, times.size());
    }

    @Test
    void narrowsToTheIntersectionOfBranchAndProfessionalHours() {
        when(professionalScheduleRepository.findByProfessionalId(professionalId))
                .thenReturn(List.of(professionalSchedule(DayOfWeek.MONDAY, LocalTime.of(14, 0), LocalTime.of(16, 0))));

        List<OffsetDateTime> slots = availabilityService.getAvailability(tenantId, professionalId, serviceId, monday);

        assertEquals(4, slots.size());
        assertEquals(LocalTime.of(14, 0), slots.get(0).toLocalTime());
    }

    @Test
    void rejectsWhenProfessionalDoesNotOfferTheService() {
        when(professionalServiceAssignmentRepository.findByProfessionalId(professionalId)).thenReturn(List.of());

        assertThrows(
                ResponseStatusException.class,
                () -> availabilityService.getAvailability(tenantId, professionalId, serviceId, monday));
    }

    @Test
    void rejectsWhenProfessionalIsInactive() {
        Professional inactive = new Professional();
        inactive.setId(professionalId);
        inactive.setBranchId(branchId);
        inactive.setActive(false);
        when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(inactive));

        assertThrows(
                ResponseStatusException.class,
                () -> availabilityService.getAvailability(tenantId, professionalId, serviceId, monday));
    }

    @Test
    void rejectsWhenServiceIsInactive() {
        Service inactiveService = new Service();
        inactiveService.setId(serviceId);
        inactiveService.setTenantId(tenantId);
        inactiveService.setActive(false);
        inactiveService.setDurationMinutes(30);
        when(serviceRepository.findById(serviceId)).thenReturn(Optional.of(inactiveService));

        assertThrows(
                ResponseStatusException.class,
                () -> availabilityService.getAvailability(tenantId, professionalId, serviceId, monday));
    }

    private BranchSchedule branchSchedule(DayOfWeek day, LocalTime open, LocalTime close) {
        BranchSchedule s = new BranchSchedule();
        s.setBranchId(branchId);
        s.setDayOfWeek(day);
        s.setOpenTime(open);
        s.setCloseTime(close);
        return s;
    }

    private ProfessionalSchedule professionalSchedule(DayOfWeek day, LocalTime open, LocalTime close) {
        ProfessionalSchedule s = new ProfessionalSchedule();
        s.setProfessionalId(professionalId);
        s.setDayOfWeek(day);
        s.setOpenTime(open);
        s.setCloseTime(close);
        return s;
    }
}

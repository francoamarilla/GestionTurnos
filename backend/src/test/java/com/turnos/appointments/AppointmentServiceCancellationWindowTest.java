package com.turnos.appointments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.turnos.availability.AvailabilityService;
import com.turnos.customers.Customer;
import com.turnos.customers.CustomerRepository;
import com.turnos.professionals.Professional;
import com.turnos.professionals.ProfessionalRepository;
import com.turnos.services.Service;
import com.turnos.services.ServiceRepository;
import com.turnos.tenancy.Branch;
import com.turnos.tenancy.BranchRepository;
import com.turnos.tenancy.Tenant;
import com.turnos.tenancy.TenantRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

// Spec rule 12: the customer's own cancel/reschedule is time-boxed by
// Tenant.cancellationWindowHours; the business's own paths (updateStatus,
// reschedule) - already verified end-to-end via docker compose in earlier
// commits - have no such restriction. No HTTP endpoint exists yet for these
// (the bot, section 8, will call them in-process), so this is unit-tested.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppointmentServiceCancellationWindowTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private AvailabilityService availabilityService;

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private ProfessionalRepository professionalRepository;

    @Mock
    private BranchRepository branchRepository;

    @Mock
    private TenantRepository tenantRepository;

    private AppointmentService appointmentService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID branchId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID appointmentId = UUID.randomUUID();
    private final UUID professionalId = UUID.randomUUID();
    private final UUID serviceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        appointmentService = new AppointmentService(
                appointmentRepository,
                availabilityService,
                serviceRepository,
                customerRepository,
                professionalRepository,
                branchRepository,
                tenantRepository);

        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setTimezone("UTC");
        tenant.setCancellationWindowHours(2);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        Branch branch = new Branch();
        branch.setId(branchId);
        branch.setTenantId(tenantId);
        when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));

        Professional professional = new Professional();
        professional.setId(professionalId);
        professional.setBranchId(branchId);
        professional.setActive(true);
        when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));

        Service service = new Service();
        service.setId(serviceId);
        service.setTenantId(tenantId);
        service.setActive(true);
        service.setDurationMinutes(30);
        service.setPrice(BigDecimal.TEN);
        when(serviceRepository.findById(serviceId)).thenReturn(Optional.of(service));

        Customer customer = new Customer();
        customer.setId(customerId);
        customer.setTenantId(tenantId);
        customer.setActive(true);
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));

        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(appointmentRepository.saveAndFlush(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Appointment confirmedAppointment(UUID ownerCustomerId, Instant startTime) {
        Appointment appointment = new Appointment();
        appointment.setId(appointmentId);
        appointment.setBranchId(branchId);
        appointment.setProfessionalId(professionalId);
        appointment.setServiceId(serviceId);
        appointment.setCustomerId(ownerCustomerId);
        appointment.setStartTime(startTime);
        appointment.setEndTime(startTime.plusSeconds(1800));
        appointment.setDurationMinutes(30);
        appointment.setPrice(BigDecimal.TEN);
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        appointment.setOrigin(AppointmentOrigin.BOT);
        return appointment;
    }

    @Test
    void customerCanCancelOutsideTheWindow() {
        Appointment appointment = confirmedAppointment(customerId, Instant.now().plusSeconds(3 * 3600));
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        appointmentService.cancelAsCustomer(tenantId, customerId, appointmentId);

        assertEquals(AppointmentStatus.CANCELLED, appointment.getStatus());
        verify(appointmentRepository).save(appointment);
    }

    @Test
    void customerCannotCancelInsideTheWindow() {
        Appointment appointment = confirmedAppointment(customerId, Instant.now().plusSeconds(3600));
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        assertThrows(
                ResponseStatusException.class,
                () -> appointmentService.cancelAsCustomer(tenantId, customerId, appointmentId));
        assertEquals(AppointmentStatus.CONFIRMED, appointment.getStatus());
    }

    @Test
    void customerCannotCancelSomeoneElsesAppointment() {
        Appointment appointment = confirmedAppointment(UUID.randomUUID(), Instant.now().plusSeconds(3 * 3600));
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        assertThrows(
                ResponseStatusException.class,
                () -> appointmentService.cancelAsCustomer(tenantId, customerId, appointmentId));
    }

    @Test
    void customerCannotCancelANonConfirmedAppointment() {
        Appointment appointment = confirmedAppointment(customerId, Instant.now().plusSeconds(3 * 3600));
        appointment.setStatus(AppointmentStatus.CANCELLED);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        assertThrows(
                ResponseStatusException.class,
                () -> appointmentService.cancelAsCustomer(tenantId, customerId, appointmentId));
    }

    @Test
    void rescheduleAsCustomerSucceedsOutsideTheWindow() {
        Appointment appointment = confirmedAppointment(customerId, Instant.now().plusSeconds(3 * 3600));
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        OffsetDateTime newStart = OffsetDateTime.now(ZoneOffset.UTC).plusHours(5);
        when(availabilityService.getAvailability(tenantId, professionalId, serviceId, newStart.toLocalDate()))
                .thenReturn(List.of(newStart));

        RescheduleAppointmentRequest request = new RescheduleAppointmentRequest(newStart, null, null);
        AppointmentResponse response =
                appointmentService.rescheduleAsCustomer(tenantId, customerId, appointmentId, request);

        assertEquals(AppointmentStatus.CANCELLED, appointment.getStatus());
        assertEquals(appointmentId, response.rescheduledFromAppointmentId());
    }

    @Test
    void rescheduleAsCustomerRejectedInsideTheWindow() {
        Appointment appointment = confirmedAppointment(customerId, Instant.now().plusSeconds(3600));
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        RescheduleAppointmentRequest request =
                new RescheduleAppointmentRequest(OffsetDateTime.now(ZoneOffset.UTC).plusHours(5), null, null);

        assertThrows(
                ResponseStatusException.class,
                () -> appointmentService.rescheduleAsCustomer(tenantId, customerId, appointmentId, request));
        assertEquals(AppointmentStatus.CONFIRMED, appointment.getStatus());
    }
}

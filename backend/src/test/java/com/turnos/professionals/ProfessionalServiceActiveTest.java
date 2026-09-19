package com.turnos.professionals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.turnos.appointments.AppointmentRepository;
import com.turnos.appointments.AppointmentStatus;
import com.turnos.services.ServiceRepository;
import com.turnos.tenancy.Branch;
import com.turnos.tenancy.BranchRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// Backlog item 1: PATCH /{id} accepts `active`, the only way a soft-deleted
// professional comes back. The half worth a test is that the rule-15 guard
// DELETE /{id} has must also cover active=false here - otherwise the update
// path is a way around it.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProfessionalServiceActiveTest {

    @Mock
    private BranchRepository branchRepository;

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

    private ProfessionalService professionalService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID branchId = UUID.randomUUID();
    private final UUID professionalId = UUID.randomUUID();
    private Professional professional;

    @BeforeEach
    void setUp() {
        professionalService = new ProfessionalService(
                branchRepository,
                professionalRepository,
                professionalScheduleRepository,
                professionalServiceAssignmentRepository,
                serviceRepository,
                appointmentRepository);

        Branch branch = new Branch();
        branch.setId(branchId);
        branch.setTenantId(tenantId);
        when(branchRepository.findByTenantId(tenantId)).thenReturn(Optional.of(branch));

        professional = new Professional();
        professional.setId(professionalId);
        professional.setBranchId(branchId);
        professional.setName("Nico");
        professional.setActive(false);
        when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));
        when(professionalRepository.save(any(Professional.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void reactivatesAnInactiveProfessional() {
        ProfessionalResponse response =
                professionalService.update(tenantId, professionalId, new UpdateProfessionalRequest(null, null, true));

        assertTrue(response.active());
        assertTrue(professional.isActive());
    }

    @Test
    void deactivatingThroughUpdateHonoursThePendingAppointmentsGuard() {
        professional.setActive(true);
        when(appointmentRepository.existsByProfessionalIdAndStatusAndStartTimeAfter(
                        eq(professionalId), eq(AppointmentStatus.CONFIRMED), any(Instant.class)))
                .thenReturn(true);

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> professionalService.update(
                        tenantId, professionalId, new UpdateProfessionalRequest(null, null, false)));

        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
        assertTrue(professional.isActive());
        verify(professionalRepository, never()).save(any(Professional.class));
    }

    @Test
    void deactivatesThroughUpdateWhenNothingIsPending() {
        professional.setActive(true);
        when(appointmentRepository.existsByProfessionalIdAndStatusAndStartTimeAfter(
                        eq(professionalId), eq(AppointmentStatus.CONFIRMED), any(Instant.class)))
                .thenReturn(false);

        ProfessionalResponse response =
                professionalService.update(tenantId, professionalId, new UpdateProfessionalRequest(null, null, false));

        assertFalse(response.active());
        assertFalse(professional.isActive());
    }
}

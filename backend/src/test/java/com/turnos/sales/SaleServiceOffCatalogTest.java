package com.turnos.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.turnos.appointments.Appointment;
import com.turnos.appointments.AppointmentRepository;
import com.turnos.appointments.AppointmentResponse;
import com.turnos.customers.CustomerRepository;
import com.turnos.customers.CustomerService;
import com.turnos.professionals.Professional;
import com.turnos.professionals.ProfessionalRepository;
import com.turnos.professionals.ProfessionalServiceAssignmentRepository;
import com.turnos.services.ServiceRepository;
import com.turnos.tenancy.Branch;
import com.turnos.tenancy.BranchRepository;
import com.turnos.tenancy.Tenant;
import com.turnos.tenancy.TenantRepository;
import java.math.BigDecimal;
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

// ENDPOINTS-PENDIENTES.md #5: a sale of something off the catalog ("Otro").
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SaleServiceOffCatalogTest {

    @Mock private AppointmentRepository appointmentRepository;
    @Mock private ProfessionalRepository professionalRepository;
    @Mock private ProfessionalServiceAssignmentRepository assignmentRepository;
    @Mock private ServiceRepository serviceRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private CustomerService customerService;
    @Mock private BranchRepository branchRepository;
    @Mock private TenantRepository tenantRepository;

    private SaleService saleService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID branchId = UUID.randomUUID();
    private final UUID professionalId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        saleService = new SaleService(
                appointmentRepository,
                professionalRepository,
                assignmentRepository,
                serviceRepository,
                customerRepository,
                customerService,
                branchRepository,
                tenantRepository);

        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setTimezone("UTC");
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

        when(appointmentRepository.saveAndFlush(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private CreateSaleRequest otro(String description) {
        return new CreateSaleRequest(
                professionalId, null, description, null, new BigDecimal("3000"), null, null, null, null);
    }

    @Test
    void storesTheDescriptionAndAShortFixedSlot() {
        AppointmentResponse response = saleService.create(tenantId, UUID.randomUUID(), otro("  Pomada  "));

        assertNull(response.serviceId());
        assertEquals("Pomada", response.description());
        assertEquals(SaleService.OFF_CATALOG_MINUTES, response.durationMinutes());
    }

    @Test
    void rejectsASaleWithNeitherServiceNorDescription() {
        assertThrows(ResponseStatusException.class, () -> saleService.create(tenantId, UUID.randomUUID(), otro(" ")));
        verify(appointmentRepository, never()).saveAndFlush(any());
    }
}

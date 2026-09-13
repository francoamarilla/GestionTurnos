package com.turnos.establishment;

import com.turnos.tenancy.Branch;
import com.turnos.tenancy.BranchRepository;
import com.turnos.tenancy.BranchSchedule;
import com.turnos.tenancy.BranchScheduleRepository;
import com.turnos.tenancy.Tenant;
import com.turnos.tenancy.TenantRepository;
import com.turnos.whatsapp.WhatsAppIntegrationRepository;
import java.time.DayOfWeek;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EstablishmentService {

    private final TenantRepository tenantRepository;
    private final BranchRepository branchRepository;
    private final BranchScheduleRepository branchScheduleRepository;
    private final WhatsAppIntegrationRepository whatsAppIntegrationRepository;

    public EstablishmentService(
            TenantRepository tenantRepository,
            BranchRepository branchRepository,
            BranchScheduleRepository branchScheduleRepository,
            WhatsAppIntegrationRepository whatsAppIntegrationRepository) {
        this.tenantRepository = tenantRepository;
        this.branchRepository = branchRepository;
        this.branchScheduleRepository = branchScheduleRepository;
        this.whatsAppIntegrationRepository = whatsAppIntegrationRepository;
    }

    public EstablishmentResponse get(UUID tenantId) {
        Tenant tenant = getTenant(tenantId);
        Branch branch = getBranch(tenantId);
        return toResponse(tenant, branch);
    }

    @Transactional
    public EstablishmentResponse update(UUID tenantId, UpdateEstablishmentRequest request) {
        Tenant tenant = getTenant(tenantId);
        Branch branch = getBranch(tenantId);

        if (request.businessName() != null) {
            tenant.setBusinessName(request.businessName());
        }
        if (request.contactPhone() != null) {
            tenant.setContactPhone(request.contactPhone());
        }
        if (request.cancellationWindowHours() != null) {
            tenant.setCancellationWindowHours(request.cancellationWindowHours());
        }
        tenantRepository.save(tenant);

        if (request.schedule() != null) {
            replaceSchedule(branch.getId(), request.schedule());
        }

        return toResponse(tenant, branch);
    }

    // Onboarding/creation is intentionally not implemented (spec section 11:
    // "contrato de alta pendiente del BSP") - not RLS-backed (see V8
    // migration), so the tenantId filter here is the only isolation layer.
    public WhatsAppIntegrationResponse getWhatsAppIntegration(UUID tenantId) {
        return whatsAppIntegrationRepository
                .findByTenantId(tenantId)
                .map(WhatsAppIntegrationResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "WhatsApp integration not configured"));
    }

    private void replaceSchedule(UUID branchId, List<DaySchedule> schedule) {
        Map<DayOfWeek, DaySchedule> byDay = new EnumMap<>(DayOfWeek.class);
        for (DaySchedule day : schedule) {
            if (byDay.put(day.dayOfWeek(), day) != null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Duplicate day in schedule: " + day.dayOfWeek());
            }
            if (!day.closed() && (day.openTime() == null || day.closeTime() == null)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "openTime/closeTime required when not closed: " + day.dayOfWeek());
            }
        }
        if (byDay.size() != DayOfWeek.values().length) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Schedule must include all 7 days of the week");
        }

        branchScheduleRepository.deleteByBranchId(branchId);
        branchScheduleRepository.flush();
        for (DaySchedule day : schedule) {
            BranchSchedule entity = new BranchSchedule();
            entity.setBranchId(branchId);
            entity.setDayOfWeek(day.dayOfWeek());
            entity.setClosed(day.closed());
            entity.setOpenTime(day.closed() ? null : day.openTime());
            entity.setCloseTime(day.closed() ? null : day.closeTime());
            branchScheduleRepository.save(entity);
        }
    }

    private EstablishmentResponse toResponse(Tenant tenant, Branch branch) {
        Map<DayOfWeek, BranchSchedule> byDay = new EnumMap<>(DayOfWeek.class);
        for (BranchSchedule s : branchScheduleRepository.findByBranchId(branch.getId())) {
            byDay.put(s.getDayOfWeek(), s);
        }
        // A day with no row yet (e.g. brand new tenant, schedule not configured)
        // reads as closed rather than the endpoint failing.
        List<DaySchedule> schedule = List.of(DayOfWeek.values()).stream()
                .map(day -> {
                    BranchSchedule s = byDay.get(day);
                    return s == null
                            ? new DaySchedule(day, null, null, true)
                            : new DaySchedule(day, s.getOpenTime(), s.getCloseTime(), s.isClosed());
                })
                .toList();

        return new EstablishmentResponse(
                tenant.getBusinessName(),
                tenant.getContactPhone(),
                tenant.getCancellationWindowHours(),
                tenant.getTimezone(),
                schedule);
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

package com.turnos.establishment;

import com.turnos.tenancy.Branch;
import com.turnos.tenancy.BranchRepository;
import com.turnos.tenancy.BranchSchedule;
import com.turnos.tenancy.BranchScheduleRepository;
import com.turnos.tenancy.Tenant;
import com.turnos.tenancy.TenantRepository;
import com.turnos.tenancy.TenantStatus;
import com.turnos.whatsapp.WhatsAppIntegrationRepository;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EstablishmentService {

    // Same set as the V14 CHECK constraint.
    private static final Set<Integer> SLOT_INTERVALS = Set.of(15, 30, 60);

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
        if (request.address() != null) {
            tenant.setAddress(request.address().isBlank() ? null : request.address().strip());
        }
        if (request.timezone() != null) {
            if (!ZoneId.getAvailableZoneIds().contains(request.timezone())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown timezone: " + request.timezone());
            }
            tenant.setTimezone(request.timezone());
        }
        if (request.bookingHorizonDays() != null) {
            int days = request.bookingHorizonDays();
            if (days < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bookingHorizonDays cannot be negative");
            }
            tenant.setBookingHorizonDays(days == 0 ? null : days);
        }
        if (request.slotIntervalMinutes() != null) {
            if (!SLOT_INTERVALS.contains(request.slotIntervalMinutes())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "slotIntervalMinutes must be one of " + SLOT_INTERVALS);
            }
            tenant.setSlotIntervalMinutes(request.slotIntervalMinutes());
        }
        if (request.onlineBookingEnabled() != null) {
            tenant.setOnlineBookingEnabled(request.onlineBookingEnabled());
        }
        if (request.remindersEnabled() != null) {
            tenant.setRemindersEnabled(request.remindersEnabled());
        }
        tenantRepository.save(tenant);

        if (request.schedule() != null) {
            replaceSchedule(branch.getId(), request.schedule());
        }

        return toResponse(tenant, branch);
    }

    // "Cerrar el negocio en la plataforma" (ENDPOINTS-PENDIENTES.md #9).
    // Nothing is deleted: the history stays readable, the tenant just stops
    // taking new work - no online booking, and no new appointment or sale
    // from the panel either (guarded in AppointmentService/SaleService).
    // Only the Super Admin can bring it back (PATCH /api/admin/tenants/{id}).
    @Transactional
    public EstablishmentResponse deactivate(UUID tenantId) {
        Tenant tenant = getTenant(tenantId);
        if (tenant.getStatus() == TenantStatus.SUSPENDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Business is already closed");
        }
        tenant.setStatus(TenantStatus.SUSPENDED);
        tenant.setOnlineBookingEnabled(false);
        tenantRepository.save(tenant);
        return toResponse(tenant, getBranch(tenantId));
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
            List<TimeRange> sorted = (day.ranges() == null ? List.<TimeRange>of() : day.ranges())
                    .stream()
                    .sorted(Comparator.comparing(TimeRange::openTime))
                    .toList();
            for (int i = 0; i < sorted.size(); i++) {
                TimeRange range = sorted.get(i);
                if (range.openTime() == null || range.closeTime() == null) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "openTime/closeTime required in every range: " + day.dayOfWeek());
                }
                if (!range.openTime().isBefore(range.closeTime())) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "A range must end after it starts: " + day.dayOfWeek());
                }
                if (i > 0 && range.openTime().isBefore(sorted.get(i - 1).closeTime())) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Overlapping ranges in " + day.dayOfWeek());
                }
            }
        }
        if (byDay.size() != DayOfWeek.values().length) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Schedule must include all 7 days of the week");
        }

        branchScheduleRepository.deleteByBranchId(branchId);
        branchScheduleRepository.flush();
        for (DaySchedule day : schedule) {
            for (TimeRange range : day.ranges()) {
                BranchSchedule entity = new BranchSchedule();
                entity.setBranchId(branchId);
                entity.setDayOfWeek(day.dayOfWeek());
                entity.setOpenTime(range.openTime());
                entity.setCloseTime(range.closeTime());
                branchScheduleRepository.save(entity);
            }
        }
    }

    private EstablishmentResponse toResponse(Tenant tenant, Branch branch) {
        Map<DayOfWeek, List<TimeRange>> byDay = new EnumMap<>(DayOfWeek.class);
        for (BranchSchedule s : branchScheduleRepository.findByBranchId(branch.getId())) {
            byDay.computeIfAbsent(s.getDayOfWeek(), d -> new ArrayList<>())
                    .add(new TimeRange(s.getOpenTime(), s.getCloseTime()));
        }
        byDay.values().forEach(ranges -> ranges.sort(Comparator.comparing(TimeRange::openTime)));
        // A day with no rows is a day the branch doesn't open - including a
        // brand new tenant whose schedule nobody configured yet.
        List<DaySchedule> schedule = List.of(DayOfWeek.values()).stream()
                .map(day -> new DaySchedule(day, byDay.getOrDefault(day, List.of())))
                .toList();

        return new EstablishmentResponse(
                tenant.getStatus(),
                tenant.getBusinessName(),
                tenant.getContactPhone(),
                tenant.getAddress(),
                tenant.getCancellationWindowHours(),
                tenant.getBookingHorizonDays(),
                tenant.getSlotIntervalMinutes(),
                tenant.isOnlineBookingEnabled(),
                tenant.isRemindersEnabled(),
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

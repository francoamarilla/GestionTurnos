package com.turnos.professionals;

import com.turnos.services.Service;
import com.turnos.services.ServiceRepository;
import com.turnos.tenancy.Branch;
import com.turnos.tenancy.BranchRepository;
import java.time.DayOfWeek;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// Fully-qualifies @org.springframework.stereotype.Service below instead of
// importing it, since com.turnos.services.Service (the catalog entity, used
// by the service-assignment methods) is imported above and would collide.
@org.springframework.stereotype.Service
public class ProfessionalService {

    private final BranchRepository branchRepository;
    private final ProfessionalRepository professionalRepository;
    private final ProfessionalScheduleRepository professionalScheduleRepository;
    private final ProfessionalServiceAssignmentRepository professionalServiceAssignmentRepository;
    private final ServiceRepository serviceRepository;

    public ProfessionalService(
            BranchRepository branchRepository,
            ProfessionalRepository professionalRepository,
            ProfessionalScheduleRepository professionalScheduleRepository,
            ProfessionalServiceAssignmentRepository professionalServiceAssignmentRepository,
            ServiceRepository serviceRepository) {
        this.branchRepository = branchRepository;
        this.professionalRepository = professionalRepository;
        this.professionalScheduleRepository = professionalScheduleRepository;
        this.professionalServiceAssignmentRepository = professionalServiceAssignmentRepository;
        this.serviceRepository = serviceRepository;
    }

    public Page<ProfessionalResponse> findAll(UUID tenantId, Boolean active, Pageable pageable) {
        Branch branch = getBranch(tenantId);
        Page<Professional> page = active == null
                ? professionalRepository.findByBranchId(branch.getId(), pageable)
                : professionalRepository.findByBranchIdAndActive(branch.getId(), active, pageable);
        return page.map(ProfessionalResponse::from);
    }

    public ProfessionalResponse findById(UUID tenantId, UUID id) {
        return ProfessionalResponse.from(getOwnedOrThrow(tenantId, id));
    }

    public ProfessionalResponse create(UUID tenantId, CreateProfessionalRequest request) {
        Branch branch = getBranch(tenantId);
        Professional professional = new Professional();
        professional.setBranchId(branch.getId());
        professional.setName(request.name());
        professional.setContactPhone(request.contactPhone());
        professional.setActive(true);
        return ProfessionalResponse.from(professionalRepository.save(professional));
    }

    public ProfessionalResponse update(UUID tenantId, UUID id, UpdateProfessionalRequest request) {
        Professional professional = getOwnedOrThrow(tenantId, id);
        if (request.name() != null) {
            professional.setName(request.name());
        }
        if (request.contactPhone() != null) {
            professional.setContactPhone(request.contactPhone());
        }
        return ProfessionalResponse.from(professionalRepository.save(professional));
    }

    // Soft delete only (spec section 4, rule 21). The 409-if-pending-
    // appointments guard (rule 15) is added once Appointment exists.
    public void deactivate(UUID tenantId, UUID id) {
        Professional professional = getOwnedOrThrow(tenantId, id);
        professional.setActive(false);
        professionalRepository.save(professional);
    }

    public List<DaySchedule> getSchedule(UUID tenantId, UUID professionalId) {
        Professional professional = getOwnedOrThrow(tenantId, professionalId);
        Map<DayOfWeek, ProfessionalSchedule> byDay = new EnumMap<>(DayOfWeek.class);
        for (ProfessionalSchedule s : professionalScheduleRepository.findByProfessionalId(professional.getId())) {
            byDay.put(s.getDayOfWeek(), s);
        }
        return List.of(DayOfWeek.values()).stream()
                .map(day -> {
                    ProfessionalSchedule s = byDay.get(day);
                    return s == null
                            ? new DaySchedule(day, null, null, true)
                            : new DaySchedule(day, s.getOpenTime(), s.getCloseTime(), s.isClosed());
                })
                .toList();
    }

    // @Transactional is required here, not optional: deleteByProfessionalId
    // needs an active transaction to issue its remove() calls, and this only
    // surfaces once there are existing rows to delete (an empty table masks
    // it, which is how this was first missed).
    @Transactional
    public List<DaySchedule> updateSchedule(UUID tenantId, UUID professionalId, List<DaySchedule> schedule) {
        Professional professional = getOwnedOrThrow(tenantId, professionalId);

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

        professionalScheduleRepository.deleteByProfessionalId(professional.getId());
        professionalScheduleRepository.flush();
        for (DaySchedule day : schedule) {
            ProfessionalSchedule entity = new ProfessionalSchedule();
            entity.setProfessionalId(professional.getId());
            entity.setDayOfWeek(day.dayOfWeek());
            entity.setClosed(day.closed());
            entity.setOpenTime(day.closed() ? null : day.openTime());
            entity.setCloseTime(day.closed() ? null : day.closeTime());
            professionalScheduleRepository.save(entity);
        }
        return getSchedule(tenantId, professionalId);
    }

    public List<UUID> getAssignedServiceIds(UUID tenantId, UUID professionalId) {
        Professional professional = getOwnedOrThrow(tenantId, professionalId);
        return professionalServiceAssignmentRepository.findByProfessionalId(professional.getId()).stream()
                .map(ProfessionalServiceAssignment::getServiceId)
                .toList();
    }

    // Full replace (PUT semantics, spec section 11): every serviceId must
    // exist and belong to this tenant, or the whole call is rejected.
    @Transactional
    public List<UUID> updateServices(UUID tenantId, UUID professionalId, List<UUID> serviceIds) {
        Professional professional = getOwnedOrThrow(tenantId, professionalId);
        for (UUID serviceId : serviceIds) {
            Service service = serviceRepository
                    .findById(serviceId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found: " + serviceId));
            if (!service.getTenantId().equals(tenantId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found: " + serviceId);
            }
        }

        professionalServiceAssignmentRepository.deleteByProfessionalId(professional.getId());
        professionalServiceAssignmentRepository.flush();
        for (UUID serviceId : serviceIds) {
            ProfessionalServiceAssignment assignment = new ProfessionalServiceAssignment();
            assignment.setProfessionalId(professional.getId());
            assignment.setServiceId(serviceId);
            professionalServiceAssignmentRepository.save(assignment);
        }
        return getAssignedServiceIds(tenantId, professionalId);
    }

    private Professional getOwnedOrThrow(UUID tenantId, UUID id) {
        Branch branch = getBranch(tenantId);
        Professional professional = professionalRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Professional not found"));
        if (!professional.getBranchId().equals(branch.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Professional not found");
        }
        return professional;
    }

    private Branch getBranch(UUID tenantId) {
        return branchRepository
                .findByTenantId(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
    }
}

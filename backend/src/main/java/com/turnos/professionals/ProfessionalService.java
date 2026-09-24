package com.turnos.professionals;

import com.turnos.appointments.AppointmentRepository;
import com.turnos.appointments.AppointmentStatus;
import com.turnos.services.Service;
import com.turnos.services.ServiceRepository;
import com.turnos.tenancy.Branch;
import com.turnos.tenancy.BranchRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final AppointmentRepository appointmentRepository;

    public ProfessionalService(
            BranchRepository branchRepository,
            ProfessionalRepository professionalRepository,
            ProfessionalScheduleRepository professionalScheduleRepository,
            ProfessionalServiceAssignmentRepository professionalServiceAssignmentRepository,
            ServiceRepository serviceRepository,
            AppointmentRepository appointmentRepository) {
        this.branchRepository = branchRepository;
        this.professionalRepository = professionalRepository;
        this.professionalScheduleRepository = professionalScheduleRepository;
        this.professionalServiceAssignmentRepository = professionalServiceAssignmentRepository;
        this.serviceRepository = serviceRepository;
        this.appointmentRepository = appointmentRepository;
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
        if (request.active() != null) {
            if (!request.active()) {
                requireNoPendingAppointments(professional);
            }
            professional.setActive(request.active());
        }
        return ProfessionalResponse.from(professionalRepository.save(professional));
    }

    // Soft delete (spec section 4, rule 21) - blocked (rule 15) while there
    // are unresolved future CONFIRMED appointments; the Admin must cancel or
    // reassign them first.
    public void deactivate(UUID tenantId, UUID id) {
        Professional professional = getOwnedOrThrow(tenantId, id);
        requireNoPendingAppointments(professional);
        professional.setActive(false);
        professionalRepository.save(professional);
    }

    // Shared by DELETE /{id} and by PATCH /{id} with active=false, so the
    // rule-15 guard cannot be sidestepped through the update path.
    private void requireNoPendingAppointments(Professional professional) {
        boolean hasPendingAppointments = appointmentRepository.existsByProfessionalIdAndStatusAndStartTimeAfter(
                professional.getId(), AppointmentStatus.CONFIRMED, Instant.now());
        if (hasPendingAppointments) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Professional has unresolved future confirmed appointments");
        }
    }

    public List<DaySchedule> getSchedule(UUID tenantId, UUID professionalId) {
        Professional professional = getOwnedOrThrow(tenantId, professionalId);
        Map<DayOfWeek, List<TimeRange>> byDay = new EnumMap<>(DayOfWeek.class);
        for (ProfessionalSchedule s : professionalScheduleRepository.findByProfessionalId(professional.getId())) {
            byDay.computeIfAbsent(s.getDayOfWeek(), d -> new ArrayList<>())
                    .add(new TimeRange(s.getOpenTime(), s.getCloseTime()));
        }
        byDay.values().forEach(ranges -> ranges.sort(Comparator.comparing(TimeRange::openTime)));
        return List.of(DayOfWeek.values()).stream()
                .map(day -> new DaySchedule(day, byDay.getOrDefault(day, List.of())))
                .toList();
    }

    // @Transactional is required here, not optional: deleteByProfessionalId
    // needs an active transaction to issue its remove() calls, and this only
    // surfaces once there are existing rows to delete (an empty table masks
    // it, which is how this was first missed).
    @Transactional
    public List<DaySchedule> updateSchedule(UUID tenantId, UUID professionalId, List<DaySchedule> schedule) {
        Professional professional = getOwnedOrThrow(tenantId, professionalId);

        validateWeek(schedule);

        professionalScheduleRepository.deleteByProfessionalId(professional.getId());
        professionalScheduleRepository.flush();
        for (DaySchedule day : schedule) {
            for (TimeRange range : day.ranges()) {
                ProfessionalSchedule entity = new ProfessionalSchedule();
                entity.setProfessionalId(professional.getId());
                entity.setDayOfWeek(day.dayOfWeek());
                entity.setOpenTime(range.openTime());
                entity.setCloseTime(range.closeTime());
                professionalScheduleRepository.save(entity);
            }
        }
        return getSchedule(tenantId, professionalId);
    }

    // Same rules as the branch's own week (EstablishmentService): the 7
    // days always travel together, and a day's ranges must be real and not
    // step on each other.
    static void validateWeek(List<DaySchedule> schedule) {
        Map<DayOfWeek, DaySchedule> byDay = new EnumMap<>(DayOfWeek.class);
        for (DaySchedule day : schedule) {
            if (byDay.put(day.dayOfWeek(), day) != null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Duplicate day in schedule: " + day.dayOfWeek());
            }
            List<TimeRange> ranges = day.ranges() == null ? List.of() : day.ranges();
            List<TimeRange> sorted = ranges.stream()
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

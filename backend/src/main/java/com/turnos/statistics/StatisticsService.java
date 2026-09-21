package com.turnos.statistics;

import com.turnos.appointments.Appointment;
import com.turnos.appointments.AppointmentRepository;
import com.turnos.appointments.AppointmentStatus;
import com.turnos.professionals.Professional;
import com.turnos.professionals.ProfessionalRepository;
import com.turnos.professionals.ProfessionalSchedule;
import com.turnos.professionals.ProfessionalScheduleRepository;
import com.turnos.tenancy.Branch;
import com.turnos.tenancy.BranchClosureRepository;
import com.turnos.tenancy.BranchRepository;
import com.turnos.tenancy.BranchSchedule;
import com.turnos.tenancy.BranchScheduleRepository;
import com.turnos.tenancy.Tenant;
import com.turnos.tenancy.TenantRepository;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

// Spec section 7's six metrics, one aggregator endpoint. "Clientes nuevos"
// and "recurrentes" are read as a partition of the SAME population -
// customers with at least one COMPLETED appointment in the period - split by
// whether their first-ever COMPLETED appointment falls inside the period
// (new) or strictly before it (returning). Taken in complete isolation,
// "recurrentes" ("ya tenían un turno completed antes del inicio") could also
// be read as counting every historical customer regardless of activity this
// period, but that wouldn't pair sensibly with "nuevos" as two views of the
// same cohort - flagging this reading in case it's wrong.
@Service
public class StatisticsService {

    private final AppointmentRepository appointmentRepository;
    private final ProfessionalRepository professionalRepository;
    private final ProfessionalScheduleRepository professionalScheduleRepository;
    private final BranchRepository branchRepository;
    private final BranchScheduleRepository branchScheduleRepository;
    private final BranchClosureRepository branchClosureRepository;
    private final TenantRepository tenantRepository;

    public StatisticsService(
            AppointmentRepository appointmentRepository,
            ProfessionalRepository professionalRepository,
            ProfessionalScheduleRepository professionalScheduleRepository,
            BranchRepository branchRepository,
            BranchScheduleRepository branchScheduleRepository,
            BranchClosureRepository branchClosureRepository,
            TenantRepository tenantRepository) {
        this.appointmentRepository = appointmentRepository;
        this.professionalRepository = professionalRepository;
        this.professionalScheduleRepository = professionalScheduleRepository;
        this.branchRepository = branchRepository;
        this.branchScheduleRepository = branchScheduleRepository;
        this.branchClosureRepository = branchClosureRepository;
        this.tenantRepository = tenantRepository;
    }

    public StatisticsSummaryResponse summary(UUID tenantId, LocalDate from, LocalDate to) {
        Tenant tenant = getTenant(tenantId);
        ZoneId zone = ZoneId.of(tenant.getTimezone());
        Branch branch = getBranch(tenantId);

        Instant fromInstant = from.atStartOfDay(zone).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(zone).toInstant();
        List<Appointment> periodAppointments =
                appointmentRepository.search(branch.getId(), fromInstant, toInstant, null, null);

        List<Appointment> completed = periodAppointments.stream()
                .filter(a -> a.getStatus() == AppointmentStatus.COMPLETED)
                .toList();

        BigDecimal totalSales = completed.stream().map(Appointment::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ProfessionalAmount> salesByProfessional = completed.stream()
                .collect(Collectors.groupingBy(
                        Appointment::getProfessionalId,
                        Collectors.reducing(BigDecimal.ZERO, Appointment::getPrice, BigDecimal::add)))
                .entrySet().stream()
                .map(e -> new ProfessionalAmount(e.getKey(), e.getValue()))
                .toList();

        // Off-catalog sales ("Otro", no serviceId) count together as one
        // row with serviceId null. groupingBy rejects null keys, hence the
        // Optional.
        List<ServiceCount> topServices = completed.stream()
                .collect(Collectors.groupingBy(a -> Optional.ofNullable(a.getServiceId()), Collectors.counting()))
                .entrySet().stream()
                .map(e -> new ServiceCount(e.getKey().orElse(null), e.getValue()))
                .sorted((a, b) -> Long.compare(b.count(), a.count()))
                .toList();

        int newCustomers = 0;
        int returningCustomers = 0;
        Set<UUID> customerIdsThisPeriod = completed.stream()
                .map(Appointment::getCustomerId)
                .filter(id -> id != null)
                .collect(Collectors.toCollection(HashSet::new));
        for (UUID customerId : customerIdsThisPeriod) {
            Instant firstCompletedEver = appointmentRepository.findEarliestStartTimeByCustomerIdAndStatus(
                    customerId, AppointmentStatus.COMPLETED);
            if (firstCompletedEver != null && !firstCompletedEver.isBefore(fromInstant)) {
                newCustomers++;
            } else {
                returningCustomers++;
            }
        }

        long cancelledCount = periodAppointments.stream()
                .filter(a -> a.getStatus() == AppointmentStatus.CANCELLED)
                .count();
        long cancellationDenominator = periodAppointments.stream()
                .filter(a -> a.getStatus() == AppointmentStatus.COMPLETED
                        || a.getStatus() == AppointmentStatus.CANCELLED
                        || a.getStatus() == AppointmentStatus.NO_SHOW)
                .count();
        double cancellationRate = cancellationDenominator == 0 ? 0.0 : (double) cancelledCount / cancellationDenominator;

        long noShowCount = periodAppointments.stream()
                .filter(a -> a.getStatus() == AppointmentStatus.NO_SHOW)
                .count();
        long noShowDenominator = periodAppointments.stream()
                .filter(a -> a.getStatus() == AppointmentStatus.COMPLETED || a.getStatus() == AppointmentStatus.NO_SHOW)
                .count();
        double noShowRate = noShowDenominator == 0 ? 0.0 : (double) noShowCount / noShowDenominator;

        OccupancyResult occupancy = computeOccupancy(branch, periodAppointments, from, to);

        return new StatisticsSummaryResponse(
                totalSales,
                salesByProfessional,
                topServices,
                newCustomers,
                returningCustomers,
                cancellationRate,
                noShowRate,
                occupancy.overallRate(),
                occupancy.byProfessional());
    }

    private record OccupancyResult(double overallRate, List<ProfessionalOccupancy> byProfessional) {
    }

    // "Horas de turnos reservados" = every non-CANCELLED appointment (the
    // same exclusion used everywhere else in the spec, e.g. the DB overlap
    // constraint). "Disponibilidad real" = the professional's configured
    // weekly hours ∩ the branch's, minus branch closures for that date - the
    // scheduled capacity, not remaining free slots (existing appointments
    // are NOT subtracted here, unlike GET /api/availability).
    private OccupancyResult computeOccupancy(
            Branch branch, List<Appointment> periodAppointments, LocalDate from, LocalDate to) {
        List<Professional> professionals =
                professionalRepository.findByBranchId(branch.getId(), Pageable.unpaged()).getContent();

        Map<DayOfWeek, BranchSchedule> branchScheduleByDay = new EnumMap<>(DayOfWeek.class);
        for (BranchSchedule s : branchScheduleRepository.findByBranchId(branch.getId())) {
            branchScheduleByDay.put(s.getDayOfWeek(), s);
        }
        Set<LocalDate> closures = branchClosureRepository.findByBranchId(branch.getId()).stream()
                .map(c -> c.getDate())
                .filter(d -> !d.isBefore(from) && !d.isAfter(to))
                .collect(Collectors.toSet());

        Map<UUID, Long> bookedMinutesByProfessional = periodAppointments.stream()
                .filter(a -> a.getStatus() != AppointmentStatus.CANCELLED)
                .collect(Collectors.groupingBy(Appointment::getProfessionalId, Collectors.summingLong(Appointment::getDurationMinutes)));

        List<ProfessionalOccupancy> byProfessional = new ArrayList<>();
        long totalBooked = 0;
        long totalAvailable = 0;
        for (Professional professional : professionals) {
            Map<DayOfWeek, ProfessionalSchedule> professionalScheduleByDay = new EnumMap<>(DayOfWeek.class);
            for (ProfessionalSchedule s : professionalScheduleRepository.findByProfessionalId(professional.getId())) {
                professionalScheduleByDay.put(s.getDayOfWeek(), s);
            }
            long availableMinutes =
                    computeAvailableMinutes(from, to, branchScheduleByDay, professionalScheduleByDay, closures);
            long bookedMinutes = bookedMinutesByProfessional.getOrDefault(professional.getId(), 0L);

            double rate = availableMinutes == 0 ? 0.0 : (double) bookedMinutes / availableMinutes;
            byProfessional.add(new ProfessionalOccupancy(professional.getId(), rate));

            totalBooked += bookedMinutes;
            totalAvailable += availableMinutes;
        }

        double overallRate = totalAvailable == 0 ? 0.0 : (double) totalBooked / totalAvailable;
        return new OccupancyResult(overallRate, byProfessional);
    }

    private long computeAvailableMinutes(
            LocalDate from,
            LocalDate to,
            Map<DayOfWeek, BranchSchedule> branchScheduleByDay,
            Map<DayOfWeek, ProfessionalSchedule> professionalScheduleByDay,
            Set<LocalDate> closures) {
        long totalMinutes = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (closures.contains(date)) {
                continue;
            }
            BranchSchedule branchDay = branchScheduleByDay.get(date.getDayOfWeek());
            if (branchDay == null || branchDay.isClosed() || branchDay.getOpenTime() == null || branchDay.getCloseTime() == null) {
                continue;
            }
            ProfessionalSchedule professionalDay = professionalScheduleByDay.get(date.getDayOfWeek());
            if (professionalDay == null
                    || professionalDay.isClosed()
                    || professionalDay.getOpenTime() == null
                    || professionalDay.getCloseTime() == null) {
                continue;
            }
            LocalTime open = laterOf(branchDay.getOpenTime(), professionalDay.getOpenTime());
            LocalTime close = earlierOf(branchDay.getCloseTime(), professionalDay.getCloseTime());
            long minutes = (close.toSecondOfDay() - open.toSecondOfDay()) / 60;
            if (minutes > 0) {
                totalMinutes += minutes;
            }
        }
        return totalMinutes;
    }

    private LocalTime laterOf(LocalTime a, LocalTime b) {
        return a.isAfter(b) ? a : b;
    }

    private LocalTime earlierOf(LocalTime a, LocalTime b) {
        return a.isBefore(b) ? a : b;
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

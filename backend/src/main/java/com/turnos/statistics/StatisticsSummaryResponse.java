package com.turnos.statistics;

import java.math.BigDecimal;
import java.util.List;

// The six metrics from spec section 7, exact formulas. "Cantidad de turnos"
// was explicitly removed from the MVP - not present here on purpose.
public record StatisticsSummaryResponse(
        BigDecimal totalSales,
        List<ProfessionalAmount> salesByProfessional,
        List<ServiceCount> topServices,
        int newCustomers,
        int returningCustomers,
        double cancellationRate,
        double noShowRate,
        double occupancyRate,
        List<ProfessionalOccupancy> occupancyByProfessional) {
}

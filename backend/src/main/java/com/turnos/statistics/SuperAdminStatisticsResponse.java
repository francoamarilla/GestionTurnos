package com.turnos.statistics;

// "Mínimo para Super Admin" (spec section 11) - no UI polish invested in
// this role for the MVP, so this stays a simple tenant-status breakdown.
public record SuperAdminStatisticsResponse(
        long totalTenants, long activeTenants, long suspendedTenants, long trialTenants) {
}

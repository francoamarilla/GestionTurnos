package com.turnos.statistics;

import com.turnos.tenancy.Tenant;
import com.turnos.tenancy.TenantRepository;
import com.turnos.tenancy.TenantStatus;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SuperAdminStatisticsService {

    private final TenantRepository tenantRepository;

    public SuperAdminStatisticsService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    public SuperAdminStatisticsResponse summary() {
        List<Tenant> tenants = tenantRepository.findAll();
        long active = tenants.stream().filter(t -> t.getStatus() == TenantStatus.ACTIVE).count();
        long suspended = tenants.stream().filter(t -> t.getStatus() == TenantStatus.SUSPENDED).count();
        long trial = tenants.stream().filter(t -> t.getStatus() == TenantStatus.TRIAL).count();
        return new SuperAdminStatisticsResponse(tenants.size(), active, suspended, trial);
    }
}

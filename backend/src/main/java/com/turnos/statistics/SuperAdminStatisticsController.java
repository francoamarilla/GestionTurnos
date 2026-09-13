package com.turnos.statistics;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Falls under SecurityConfig's /api/admin/** rule (ROLE_SUPER_ADMIN only),
// not re-checked here.
@RestController
@RequestMapping("/api/admin/statistics")
public class SuperAdminStatisticsController {

    private final SuperAdminStatisticsService superAdminStatisticsService;

    public SuperAdminStatisticsController(SuperAdminStatisticsService superAdminStatisticsService) {
        this.superAdminStatisticsService = superAdminStatisticsService;
    }

    @GetMapping
    public SuperAdminStatisticsResponse summary() {
        return superAdminStatisticsService.summary();
    }
}

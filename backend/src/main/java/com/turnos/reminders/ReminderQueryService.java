package com.turnos.reminders;

import com.turnos.tenancy.BranchRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReminderQueryService {

    private final BranchRepository branchRepository;
    private final ReminderSentRepository reminderSentRepository;

    public ReminderQueryService(BranchRepository branchRepository, ReminderSentRepository reminderSentRepository) {
        this.branchRepository = branchRepository;
        this.reminderSentRepository = reminderSentRepository;
    }

    public List<FailedReminderResponse> findFailed(UUID tenantId) {
        var branch = branchRepository
                .findByTenantId(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
        return reminderSentRepository
                .findFailedByBranchId(branch.getId(), ReminderRetryPolicy.MAX_ATTEMPTS)
                .stream()
                .map(FailedReminderResponse::from)
                .toList();
    }
}

package com.turnos.reminders;

import com.turnos.auth.AuthenticatedPrincipal;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reminders")
public class ReminderController {

    private final ReminderQueryService reminderQueryService;

    public ReminderController(ReminderQueryService reminderQueryService) {
        this.reminderQueryService = reminderQueryService;
    }

    @GetMapping("/failed")
    public List<FailedReminderResponse> failed(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return reminderQueryService.findFailed(principal.tenantId());
    }
}

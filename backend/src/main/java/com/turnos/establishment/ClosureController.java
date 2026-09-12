package com.turnos.establishment;

import com.turnos.auth.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/establishment/closures")
public class ClosureController {

    private final ClosureService closureService;

    public ClosureController(ClosureService closureService) {
        this.closureService = closureService;
    }

    @GetMapping
    public List<ClosureResponse> findAll(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return closureService.findAll(principal.tenantId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClosureResponse create(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody CreateClosureRequest request) {
        return closureService.create(principal.tenantId(), request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        closureService.delete(principal.tenantId(), id);
    }
}

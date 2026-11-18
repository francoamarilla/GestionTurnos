package com.turnos.assistantconfig;

import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// Versioned owner configuration: edit a DRAFT, try it in the playground,
// publish. Each AI turn records which version answered it (ai_turn).
@Service
public class AssistantConfigService {

    private final AssistantConfigRepository repository;

    public AssistantConfigService(AssistantConfigRepository repository) {
        this.repository = repository;
    }

    // What customers get: the published version, or the defaults.
    public AssistantConfig published(UUID tenantId) {
        return repository.findByTenantIdAndStatus(tenantId, "PUBLISHED").orElseGet(() -> defaults(tenantId));
    }

    // What the playground tries: the draft if there is one.
    public AssistantConfig draftOrPublished(UUID tenantId) {
        return repository.findByTenantIdAndStatus(tenantId, "DRAFT").orElseGet(() -> published(tenantId));
    }

    public AssistantConfigResponse get(UUID tenantId) {
        return new AssistantConfigResponse(
                AssistantConfigResponse.View.from(published(tenantId)),
                repository.findByTenantIdAndStatus(tenantId, "DRAFT").map(AssistantConfigResponse.View::from).orElse(null),
                repository.findTop20ByTenantIdOrderByVersionDesc(tenantId).stream()
                        .map(c -> new AssistantConfigResponse.VersionSummary(
                                c.getVersion(), c.getStatus(), c.getCreatedAt(), c.getPublishedAt()))
                        .toList());
    }

    @Transactional
    public AssistantConfigResponse saveDraft(UUID tenantId, UUID adminId, AssistantConfigRequest request) {
        AssistantConfig draft = repository.findByTenantIdAndStatus(tenantId, "DRAFT").orElseGet(() -> {
            AssistantConfig c = new AssistantConfig();
            c.setTenantId(tenantId);
            c.setStatus("DRAFT");
            c.setVersion(repository.findTopByTenantIdOrderByVersionDesc(tenantId)
                    .map(AssistantConfig::getVersion)
                    .orElse(0) + 1);
            return c;
        });
        request.applyTo(draft);
        draft.setCreatedBy(adminId);
        draft.setCreatedAt(Instant.now());
        repository.save(draft);
        return get(tenantId);
    }

    @Transactional
    public AssistantConfigResponse publish(UUID tenantId) {
        AssistantConfig draft = repository
                .findByTenantIdAndStatus(tenantId, "DRAFT")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "There is no draft to publish"));
        repository.findByTenantIdAndStatus(tenantId, "PUBLISHED").ifPresent(old -> {
            old.setStatus("ARCHIVED");
            repository.saveAndFlush(old);
        });
        draft.setStatus("PUBLISHED");
        draft.setPublishedAt(Instant.now());
        repository.save(draft);
        return get(tenantId);
    }

    @Transactional
    public AssistantConfigResponse discardDraft(UUID tenantId) {
        repository.findByTenantIdAndStatus(tenantId, "DRAFT").ifPresent(repository::delete);
        return get(tenantId);
    }

    private static AssistantConfig defaults(UUID tenantId) {
        AssistantConfig c = new AssistantConfig();
        c.setTenantId(tenantId);
        c.setVersion(0);
        c.setStatus("DEFAULT");
        return c;
    }
}

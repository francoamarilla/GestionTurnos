package com.turnos.services;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// Fully-qualifies @org.springframework.stereotype.Service below instead of
// importing it, since this package's entity is itself named Service.
@org.springframework.stereotype.Service
public class ServiceCatalogService {

    private final ServiceRepository serviceRepository;

    public ServiceCatalogService(ServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    public List<ServiceResponse> findAll(UUID tenantId) {
        return serviceRepository.findByTenantId(tenantId).stream().map(ServiceResponse::from).toList();
    }

    public ServiceResponse findById(UUID tenantId, UUID id) {
        return ServiceResponse.from(getOwnedOrThrow(tenantId, id));
    }

    public ServiceResponse create(UUID tenantId, CreateServiceRequest request) {
        Service service = new Service();
        service.setTenantId(tenantId);
        service.setName(request.name());
        service.setDescription(request.description());
        service.setDurationMinutes(request.durationMinutes());
        service.setPrice(request.price());
        service.setActive(true);
        return ServiceResponse.from(serviceRepository.save(service));
    }

    public ServiceResponse update(UUID tenantId, UUID id, UpdateServiceRequest request) {
        Service service = getOwnedOrThrow(tenantId, id);
        if (request.name() != null) {
            service.setName(request.name());
        }
        if (request.description() != null) {
            service.setDescription(request.description());
        }
        if (request.durationMinutes() != null) {
            service.setDurationMinutes(request.durationMinutes());
        }
        if (request.price() != null) {
            service.setPrice(request.price());
        }
        return ServiceResponse.from(serviceRepository.save(service));
    }

    // Soft delete only (spec section 4, rule 21): past appointments keep
    // their reference to this row.
    public void deactivate(UUID tenantId, UUID id) {
        Service service = getOwnedOrThrow(tenantId, id);
        service.setActive(false);
        serviceRepository.save(service);
    }

    private Service getOwnedOrThrow(UUID tenantId, UUID id) {
        Service service = serviceRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found"));
        if (!service.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found");
        }
        return service;
    }
}

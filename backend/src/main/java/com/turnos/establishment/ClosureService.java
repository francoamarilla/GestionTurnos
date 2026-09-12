package com.turnos.establishment;

import com.turnos.tenancy.Branch;
import com.turnos.tenancy.BranchClosure;
import com.turnos.tenancy.BranchClosureRepository;
import com.turnos.tenancy.BranchRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClosureService {

    private final BranchRepository branchRepository;
    private final BranchClosureRepository branchClosureRepository;

    public ClosureService(BranchRepository branchRepository, BranchClosureRepository branchClosureRepository) {
        this.branchRepository = branchRepository;
        this.branchClosureRepository = branchClosureRepository;
    }

    public List<ClosureResponse> findAll(UUID tenantId) {
        Branch branch = getBranch(tenantId);
        return branchClosureRepository.findByBranchId(branch.getId()).stream()
                .map(ClosureResponse::from)
                .toList();
    }

    public ClosureResponse create(UUID tenantId, CreateClosureRequest request) {
        Branch branch = getBranch(tenantId);
        BranchClosure closure = new BranchClosure();
        closure.setBranchId(branch.getId());
        closure.setDate(request.date());
        closure.setReason(request.reason());
        return ClosureResponse.from(branchClosureRepository.save(closure));
    }

    // Belt-and-suspenders alongside RLS (spec section 8: app-level filtering
    // plus RLS as a second layer) - RLS alone would already make a
    // cross-tenant id 404 here too.
    public void delete(UUID tenantId, UUID closureId) {
        Branch branch = getBranch(tenantId);
        BranchClosure closure = branchClosureRepository
                .findById(closureId)
                .filter(c -> c.getBranchId().equals(branch.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Closure not found"));
        branchClosureRepository.delete(closure);
    }

    private Branch getBranch(UUID tenantId) {
        return branchRepository
                .findByTenantId(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
    }
}

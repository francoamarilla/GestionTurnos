package com.turnos.tenancy;

import com.turnos.auth.AuthenticatedPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// Applies the request's tenant context (from the JWT, via SecurityContext)
// to the DB session so RLS policies can enforce isolation as a second layer
// beneath the application-level tenantId filtering already applied in every
// query (spec section 8). Relies on spring.jpa.open-in-view (Boot's default)
// keeping a single DB connection bound to the request thread, so the
// connection TenantSessionContext sets this on is the same one every query
// in this request will use. Resets it before the connection goes back to
// the pool - see TenantSessionContext for why plain set_config (session-
// scoped) is used here instead of a request-spanning transaction.
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    private final TenantSessionContext tenantSessionContext;

    public TenantContextFilter(TenantSessionContext tenantSessionContext) {
        this.tenantSessionContext = tenantSessionContext;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        applySessionContext();
        try {
            filterChain.doFilter(request, response);
        } finally {
            tenantSessionContext.reset();
        }
    }

    private void applySessionContext() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean isSuperAdmin = false;
        UUID tenantId = null;
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            isSuperAdmin = "SUPER_ADMIN".equals(principal.role());
            tenantId = principal.tenantId();
        }
        tenantSessionContext.apply(isSuperAdmin, tenantId);
    }
}

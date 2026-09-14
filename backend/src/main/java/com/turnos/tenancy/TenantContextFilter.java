package com.turnos.tenancy;

import com.turnos.auth.AuthenticatedPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// Applies the request's tenant context (from the JWT, via SecurityContext)
// to the DB session so RLS policies can enforce isolation as a second layer
// beneath the application-level tenantId filtering already applied in every
// query (spec section 8). Relies on spring.jpa.open-in-view (Boot's default)
// keeping a single DB connection bound to the request thread, so the
// connection TenantSessionContext sets this on is the same one every query
// in this request will use - which in turn requires
// hibernate.connection.handling_mode=IMMEDIATE_ACQUISITION_AND_HOLD
// (see application.properties): open-in-view's default handling mode
// only binds one *EntityManager* per request, not one physical
// *connection* - outside of an explicit transaction (true for every
// read-only, non-@Transactional query method) it still acquires and
// releases a connection per call, so under concurrent requests a query
// could silently land on a connection some other request had already
// reset, making RLS filter out rows that are actually there. Resets the
// GUC before the connection goes back to the pool - see
// TenantSessionContext for why plain set_config (session-scoped) is used
// here instead of a request-spanning transaction.
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
        UUID tenantId = applySessionContext();
        // Spec section 10: every relevant log entry carries tenantId. MDC is
        // thread-bound, so this covers everything logged for the rest of
        // this request on this thread.
        if (tenantId != null) {
            MDC.put("tenantId", tenantId.toString());
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            tenantSessionContext.reset();
            MDC.remove("tenantId");
        }
    }

    private UUID applySessionContext() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean isSuperAdmin = false;
        UUID tenantId = null;
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            isSuperAdmin = "SUPER_ADMIN".equals(principal.role());
            tenantId = principal.tenantId();
        }
        tenantSessionContext.apply(isSuperAdmin, tenantId);
        return tenantId;
    }
}

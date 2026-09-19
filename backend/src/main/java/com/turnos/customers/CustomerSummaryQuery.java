package com.turnos.customers;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

// The aggregates behind GET /api/customers/summary. Written as native SQL
// rather than a derived/JPQL repository method because the table sorts by
// computed columns (turnos, ultima visita, gastado) while paging on the
// server: the ORDER BY has to reach the aggregate, which Spring Data's
// Sort-on-@Query support cannot express, and Postgres's FILTER keeps the
// four totals in a single scan.
@Component
public class CustomerSummaryQuery {

    // API sort key -> SQL alias. A whitelist, not a convenience: the chosen
    // key is concatenated into the ORDER BY, so anything outside this map is
    // rejected rather than interpolated.
    private static final Map<String, String> SORTABLE = Map.of(
            "name", "name",
            "active", "active",
            "visits", "visits",
            "lastvisit", "lastvisit",
            "spent", "spent",
            "noshows", "noshows");

    private static final String SELECT =
            """
            SELECT c.id AS id, c.phone AS phone, c.name AS name, c.active AS active,
                   COUNT(a.id) FILTER (WHERE a.status = 'COMPLETED') AS visits,
                   MAX(a.start_time) FILTER (WHERE a.status = 'COMPLETED') AS lastvisit,
                   COALESCE(SUM(a.price) FILTER (WHERE a.status = 'COMPLETED'), 0) AS spent,
                   COUNT(a.id) FILTER (WHERE a.status = 'NO_SHOW') AS noshows
            """;

    // CAST(:param AS type) on every nullable bind: Postgres cannot infer a
    // null parameter's type on its own (the same reason CustomerRepository's
    // search casts :search), and here it would also make the IS NULL
    // short-circuit unparseable.
    private static final String FROM =
            """
            FROM customer c
            LEFT JOIN appointment a ON a.customer_id = c.id
            WHERE c.tenant_id = :tenantId
              AND (CAST(:search AS text) IS NULL
                   OR LOWER(c.name) LIKE LOWER('%' || CAST(:search AS text) || '%')
                   OR c.phone LIKE '%' || CAST(:search AS text) || '%')
              AND (CAST(:active AS boolean) IS NULL OR c.active = CAST(:active AS boolean))
            GROUP BY c.id, c.phone, c.name, c.active
            HAVING CAST(:dormantDays AS integer) IS NULL
                OR MAX(a.start_time) FILTER (WHERE a.status = 'COMPLETED')
                   < now() - make_interval(days => CAST(:dormantDays AS integer))
            """;

    @PersistenceContext
    private EntityManager entityManager;

    public Page<CustomerSummaryResponse> find(
            UUID tenantId, String search, Boolean active, Integer dormantDays, Pageable pageable, ZoneId zone) {
        Query rows = bind(
                entityManager.createNativeQuery(
                        SELECT + FROM + orderBy(pageable.getSort()) + "\nLIMIT :limit OFFSET :offset", Tuple.class),
                tenantId,
                search,
                active,
                dormantDays);
        rows.setParameter("limit", pageable.isPaged() ? pageable.getPageSize() : Integer.MAX_VALUE);
        rows.setParameter("offset", pageable.isPaged() ? pageable.getOffset() : 0L);

        @SuppressWarnings("unchecked")
        List<Tuple> tuples = rows.getResultList();
        List<CustomerSummaryResponse> content =
                tuples.stream().map(t -> toResponse(t, zone)).toList();

        // The dormant filter lives in HAVING, so the count has to repeat the
        // grouping instead of being a plain COUNT(*) over customer.
        Query count = bind(
                entityManager.createNativeQuery("SELECT count(*) FROM (SELECT c.id " + FROM + ") grouped"),
                tenantId,
                search,
                active,
                dormantDays);
        long total = ((Number) count.getSingleResult()).longValue();

        return new PageImpl<>(content, pageable, total);
    }

    private static Query bind(Query query, UUID tenantId, String search, Boolean active, Integer dormantDays) {
        return query.setParameter("tenantId", tenantId)
                .setParameter("search", search == null || search.isBlank() ? null : search.trim())
                .setParameter("active", active)
                .setParameter("dormantDays", dormantDays);
    }

    // NULLS LAST on every term on purpose: a customer who never came back
    // has a null lastvisit (and a null name is possible too), and Postgres
    // would otherwise park those rows first on a DESC sort - the opposite of
    // what "ordenar por última visita" means to someone reading the table.
    // The id tiebreaker keeps paging stable when a column repeats.
    private static String orderBy(Sort sort) {
        if (sort.isUnsorted()) {
            return "\nORDER BY name ASC NULLS LAST, id";
        }
        StringBuilder order = new StringBuilder("\nORDER BY ");
        for (Sort.Order term : sort) {
            String column = SORTABLE.get(term.getProperty().toLowerCase(Locale.ROOT));
            if (column == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Cannot sort customers by: " + term.getProperty());
            }
            order.append(column).append(term.isAscending() ? " ASC" : " DESC").append(" NULLS LAST, ");
        }
        return order.append("id").toString();
    }

    private static CustomerSummaryResponse toResponse(Tuple row, ZoneId zone) {
        Instant lastVisit = row.get("lastvisit", Instant.class);
        return new CustomerSummaryResponse(
                row.get("id", UUID.class),
                row.get("phone", String.class),
                row.get("name", String.class),
                row.get("active", Boolean.class),
                row.get("visits", Long.class),
                lastVisit == null ? null : lastVisit.atZone(zone).toOffsetDateTime(),
                row.get("spent", BigDecimal.class),
                row.get("noshows", Long.class));
    }
}

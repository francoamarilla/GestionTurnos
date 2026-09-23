package com.turnos.tenancy;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter;

// Binds the request's EntityManager - and, with
// hibernate.connection.handling_mode=IMMEDIATE_ACQUISITION_AND_HOLD, its
// single DB connection - before any other filter runs.
//
// Boot's default open-in-view is an interceptor inside DispatcherServlet,
// which is too late: TenantContextFilter runs before it, so its set_config
// went to a short-lived EntityManager whose connection went straight back
// to the pool. The request then worked on whatever connection it got next,
// and another request's reset() could clear app.tenant_id on it - RLS then
// hid rows that are there ("Tenant not found" / "Branch not found" 404s
// under concurrent requests). As a filter, the same connection carries the
// GUC from TenantContextFilter's set through its reset in the finally.
//
// Boot backs off its own open-in-view interceptor when this bean exists.
@Configuration
public class TenantSessionBindingConfig {

    @Bean
    public FilterRegistrationBean<OpenEntityManagerInViewFilter> openEntityManagerInViewFilter() {
        FilterRegistrationBean<OpenEntityManagerInViewFilter> registration =
                new FilterRegistrationBean<>(new OpenEntityManagerInViewFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}

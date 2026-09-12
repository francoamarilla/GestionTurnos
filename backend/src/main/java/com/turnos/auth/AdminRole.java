package com.turnos.auth;

// The DB check constraint also allows MANAGER so that adding it in V2 of the
// product doesn't require a migration, but the app never creates or expects
// that value today (spec section 3.1).
public enum AdminRole {
    ADMIN
}

package com.turnos.bot;

// The state machine's alphabet. Each root menu option (spec section 5) gets
// its own step; sub-steps within booking/viewing get added as those flows
// are built out.
public enum BotStep {
    ROOT_MENU,
    AWAITING_NAME,
    BOOKING_SELECT_SERVICE,
    BOOKING_SELECT_PROFESSIONAL,
    BOOKING_SELECT_SLOT,
    BOOKING_CONFIRM,
    VIEWING_APPOINTMENTS,
    HUMAN_HANDOFF
}

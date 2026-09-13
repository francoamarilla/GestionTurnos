package com.turnos.bot;

// Normalized shape the bot engine works with, deliberately decoupled from
// whatever the chosen BSP's webhook payload looks like (spec section 13 -
// that shape isn't decided yet). Once a BSP is picked, the webhook parsing
// layer (com.turnos.whatsapp) builds one of these from the real payload;
// nothing in this package needs to change.
//
// selectionId: the id of the button/list row the customer tapped, if this
// message is an interactive reply. text: free-typed text (e.g. a name, or
// text that didn't match any button). Exactly one is expected to be
// non-null for a given real message, but both are accepted here.
public record InboundMessage(String businessPhoneNumber, String customerPhoneNumber, String selectionId, String text) {
}

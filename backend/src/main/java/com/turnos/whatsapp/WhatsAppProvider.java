package com.turnos.whatsapp;

import java.util.List;

// Abstracts the WhatsApp Business Platform's own message taxonomy (text,
// approved templates, and the two interactive message types: reply buttons
// and lists) - not a specific BSP's API shape. The spec (section 9) requires
// this port so no BSP-specific code leaks into the rest of the app before
// Franco picks one (section 13); LoggingWhatsAppProvider is the only
// implementation until then.
public interface WhatsAppProvider {

    void sendText(String fromPhoneNumberId, String toPhoneNumber, String text);

    void sendTemplate(String fromPhoneNumberId, String toPhoneNumber, String templateName, List<String> parameters);

    void sendButtons(String fromPhoneNumberId, String toPhoneNumber, String bodyText, List<Button> buttons);

    void sendList(String fromPhoneNumberId, String toPhoneNumber, String bodyText, String buttonLabel, List<ListRow> rows);

    record Button(String id, String label) {
    }

    record ListRow(String id, String title, String description) {
    }
}

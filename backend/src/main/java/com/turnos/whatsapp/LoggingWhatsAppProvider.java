package com.turnos.whatsapp;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Dev/MVP-only: logs instead of calling a real BSP, since none is chosen
// yet (spec section 13). Swap for a real WhatsAppProvider implementation
// once one is - nothing else in the app should need to change.
@Component
public class LoggingWhatsAppProvider implements WhatsAppProvider {

    private static final Logger log = LoggerFactory.getLogger(LoggingWhatsAppProvider.class);

    @Override
    public void sendText(String fromPhoneNumberId, String toPhoneNumber, String text) {
        log.info("[WhatsApp fake] TEXT from={} to={} text={}", fromPhoneNumberId, toPhoneNumber, text);
    }

    @Override
    public void sendTemplate(
            String fromPhoneNumberId, String toPhoneNumber, String templateName, List<String> parameters) {
        log.info(
                "[WhatsApp fake] TEMPLATE from={} to={} template={} params={}",
                fromPhoneNumberId, toPhoneNumber, templateName, parameters);
    }

    @Override
    public void sendButtons(String fromPhoneNumberId, String toPhoneNumber, String bodyText, List<Button> buttons) {
        log.info(
                "[WhatsApp fake] BUTTONS from={} to={} body={} buttons={}",
                fromPhoneNumberId, toPhoneNumber, bodyText, buttons);
    }

    @Override
    public void sendList(
            String fromPhoneNumberId, String toPhoneNumber, String bodyText, String buttonLabel, List<ListRow> rows) {
        log.info(
                "[WhatsApp fake] LIST from={} to={} body={} buttonLabel={} rows={}",
                fromPhoneNumberId, toPhoneNumber, bodyText, buttonLabel, rows);
    }
}

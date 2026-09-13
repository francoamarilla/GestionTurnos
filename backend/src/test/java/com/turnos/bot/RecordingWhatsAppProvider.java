package com.turnos.bot;

import com.turnos.whatsapp.WhatsAppProvider;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

// Test double replacing LoggingWhatsAppProvider so bot flow tests can read
// back exactly what would have been sent - in particular the interactive
// list/button ids actually offered at each step, needed to drive a
// multi-step conversation without hardcoding IDs the test can't predict
// (e.g. a generated service/professional UUID, or which slot the
// availability grid computed first).
@Primary
@Component
public class RecordingWhatsAppProvider implements WhatsAppProvider {

    private final List<String> texts = new ArrayList<>();
    private List<ListRow> lastRows = List.of();
    private List<Button> lastButtons = List.of();

    @Override
    public void sendText(String fromPhoneNumberId, String toPhoneNumber, String text) {
        texts.add(text);
    }

    @Override
    public void sendTemplate(
            String fromPhoneNumberId, String toPhoneNumber, String templateName, List<String> parameters) {
        texts.add(templateName);
    }

    @Override
    public void sendButtons(String fromPhoneNumberId, String toPhoneNumber, String bodyText, List<Button> buttons) {
        texts.add(bodyText);
        lastButtons = buttons;
    }

    @Override
    public void sendList(
            String fromPhoneNumberId, String toPhoneNumber, String bodyText, String buttonLabel, List<ListRow> rows) {
        texts.add(bodyText);
        lastRows = rows;
    }

    public List<String> texts() {
        return texts;
    }

    public List<ListRow> lastRows() {
        return lastRows;
    }

    public List<Button> lastButtons() {
        return lastButtons;
    }

    public void reset() {
        texts.clear();
        lastRows = List.of();
        lastButtons = List.of();
    }
}

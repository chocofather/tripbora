package com.tripbora.service.destinationimport;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DestinationUpdateParserTest {
    private final DestinationImportParser parser = new DestinationImportParser();

    @Test
    void partialUpdateAllowsMissingOriginalsAndListsWhileCreateRemainsStrict() {
        String json = """
                {"version":1,"destinations":[{"external":{"tourApiContentId":"123"},
                  "translations":{"en":{"shortDescription":"New English introduction"}}}]}
                """;
        var update = parser.parseUpdateFile(json);
        assertThat(update.errors()).isEmpty();
        assertThat(update.items().get(0).errors()).isEmpty();
        assertThat(update.items().get(0).item().translation("en").shortDescription())
                .isEqualTo("New English introduction");
        assertThat(parser.parseFile(json).items().get(0).errors()).isNotEmpty();
    }

    @Test
    void updateStillRejectsUnknownFieldsInvalidEnumsAndDuplicateJsonKeys() {
        assertThat(parser.parseUpdateFile("""
                {"version":1,"destinations":[{"type":"BAD","imageUrl":"secret"}]}
                """).items().get(0).errors()).hasSize(2);
        assertThat(parser.parseUpdateFile("{\"version\":1,\"version\":1,\"destinations\":[{}]}").errors())
                .isNotEmpty();
    }
}

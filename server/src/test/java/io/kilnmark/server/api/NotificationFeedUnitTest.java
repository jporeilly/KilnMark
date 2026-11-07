package io.kilnmark.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.kilnmark.server.notify.NotificationStore;

/** The feed's items as the contract gives them: text ids, channels, team emails for admins only, no addresses. */
class NotificationFeedUnitTest {

    static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final NotificationStore.InboxRow ROW = new NotificationStore.InboxRow(41, 7L, "rule_failed", "high",
        "email not null fails on crm.customers", "312 of 48,210 rows (0.65%) fail.", "#/asset/x",
        Instant.parse("2026-10-25T06:00:00Z"), false);

    @Test
    void anItemWithoutEmailHasOnlyTheInAppChannel() throws Exception {
        JsonNode j = JSON.valueToTree(NotificationResource.item(ROW, null, null));
        assertEquals("41", j.path("id").asText());
        assertTrue(j.path("id").isTextual());
        assertEquals("rule_failed", j.path("kind").asText());
        assertEquals("2026-10-25T06:00:00Z", j.path("createdAt").asText());
        assertFalse(j.path("read").asBoolean());
        assertEquals("[{\"type\":\"in_app\"}]", j.path("channels").toString());
        assertFalse(j.has("teamEmails"), "only admins get teamEmails");
    }

    @Test
    void emailStatusAndTeamEmailsAreAdded() throws Exception {
        JsonNode j = JSON.valueToTree(NotificationResource.item(ROW,
            new NotificationStore.EmailStatus("sent", Instant.parse("2026-10-25T06:00:05Z")),
            List.of(new NotificationStore.TeamEmail("Data team", "queued"))));
        assertEquals("{\"type\":\"email\",\"status\":\"sent\",\"at\":\"2026-10-25T06:00:05Z\"}",
            j.path("channels").get(1).toString());
        assertEquals("[{\"name\":\"Data team\",\"status\":\"queued\"}]", j.path("teamEmails").toString());
        assertFalse(j.toString().contains("@"), "never an address: " + j);
        JsonNode admin = JSON.valueToTree(NotificationResource.item(ROW, null, Collections.emptyList()));
        assertEquals("[]", admin.path("teamEmails").toString(), "admins always get the list, even empty");
    }

    @Test
    void theUnreadCountStopsAt99Plus() throws Exception {
        assertEquals("{\"unread\":3}", JSON.writeValueAsString(NotificationResource.Unread.of(3)));
        assertEquals("{\"unread\":99}", JSON.writeValueAsString(NotificationResource.Unread.of(99)));
        assertEquals("{\"unread\":99,\"more\":true}", JSON.writeValueAsString(NotificationResource.Unread.of(100)),
            "still a number, with more: true, for the bell's 99+");
    }

    @Test
    void idsAreChecked() {
        assertEquals(List.of(3L, 4L), NotificationResource.ids(List.of("3", " 4 ")));
        assertThrows(IllegalArgumentException.class, () -> NotificationResource.ids(List.of("x")));
        assertThrows(IllegalArgumentException.class, () -> NotificationResource.ids(Collections.nCopies(501, "1")));
        assertNull(new NotificationResource.ReadBody(null, null).all());
    }

    @Test
    void everyKindIsOffered() {
        assertEquals(List.of("rule_failed", "rule_recovered", "issue_opened", "scan_failed", "drift", "connection_failed",
            "pipeline_failed", "pipeline_low_quality", "flow_failed", "flow_notice", "pipeline_recovered", "flow_recovered",
            "run_taking_long"),
            NotificationResource.kindChoices().stream().map(NotificationResource.KindChoice::id).toList());
    }
}

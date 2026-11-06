package az.fitnest.notifications.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import az.fitnest.notifications.model.entity.Notification;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationRoutingTest {

    @Test
    void newGymKeepsGymIdAndFillsTheRoute() {
        Map<String, String> source = new HashMap<>();
        source.put("type", "NEW_GYM");
        source.put("gymId", "1245");

        Map<String, String> enriched = NotificationRouting.enrich(source);

        assertEquals("1245", source.get("gymId"));
        assertNull(source.get("deeplink"));
        assertEquals("NEW_GYM", enriched.get("type"));
        assertEquals("GYM", enriched.get("targetType"));
        assertEquals("1245", enriched.get("targetId"));
        assertEquals("1245", enriched.get("gymId"));
        assertEquals("fitnest://gyms/1245", enriched.get("deeplink"));
    }

    @Test
    void campaignRouteIsBuiltWhenTheCallerOmitsIt() {
        Map<String, String> enriched = NotificationRouting.enrich(Map.of(
                "type", "CAMPAIGN",
                "targetId", "87"));

        assertEquals("CAMPAIGN", enriched.get("targetType"));
        assertEquals("fitnest://campaigns/87", enriched.get("deeplink"));
    }

    @Test
    void callerDeeplinkIsKept() {
        Map<String, String> enriched = NotificationRouting.enrich(Map.of(
                "type", "NEW_GYM",
                "gymId", "9",
                "deeplink", "fitnest://gyms/9"));

        assertEquals("fitnest://gyms/9", enriched.get("deeplink"));
    }

    @Test
    void otherEventsStayInboxOnly() {
        Notification notification = new Notification();
        NotificationRouting.apply(notification, Map.of("type", "PRIMARY_GYM_DISABLED", "gymId", "10"));

        assertEquals("PRIMARY_GYM_DISABLED", notification.getType());
        assertNull(notification.getTargetType());
        assertNull(notification.getTargetId());
        assertNull(notification.getDeeplink());
    }

    @Test
    void unsafeIdDoesNotBecomeALink() {
        Map<String, String> enriched = NotificationRouting.enrich(Map.of(
                "type", "NEW_GYM",
                "gymId", "12 45"));

        assertNull(enriched.get("deeplink"));
        assertNull(enriched.get("targetId"));
    }
}

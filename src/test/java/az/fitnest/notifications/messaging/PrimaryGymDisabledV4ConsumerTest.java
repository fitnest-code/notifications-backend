package az.fitnest.notifications.messaging;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import az.fitnest.notifications.grpc.IdentityGrpcClient;
import az.fitnest.notifications.repository.PrimaryGymDeliveryV4Repository;
import az.fitnest.notifications.service.NotificationService;
import java.util.*;
import org.junit.jupiter.api.Test;

class PrimaryGymDisabledV4ConsumerTest {
  @Test
  void usesRecipientLanguageAndSkipsDeliveredRecipients() {
    var identity = mock(IdentityGrpcClient.class);
    var notifications = mock(NotificationService.class);
    var delivered = mock(PrimaryGymDeliveryV4Repository.class);
    when(identity.getUsersLanguageByIds(List.of(1L, 2L)))
        .thenReturn(Map.of(1L, new IdentityGrpcClient.UserLanguageDto(1L, "RU")));
    when(delivered.existsById("event:2")).thenReturn(true);
    var event = new HashMap<String, Object>();
    event.put("eventId", "event");
    event.put("gymId", 10L);
    event.put("userIds", List.of(1L, 2L));
    event.put(
        "contents",
        Map.of(
            "AZ",
            Map.of("title", "AZ title", "body", "AZ body"),
            "RU",
            Map.of("title", "RU title", "body", "RU body"),
            "EN",
            Map.of("title", "EN title", "body", "EN body")));
    new PrimaryGymDisabledV4Consumer(identity, notifications, delivered).consume(event);
    verify(notifications).sendPushToUser(eq(1L), eq("RU title"), eq("RU body"), anyMap());
    verify(notifications, never()).sendPushToUser(eq(2L), anyString(), anyString(), anyMap());
    verify(delivered).save(any());
  }
}

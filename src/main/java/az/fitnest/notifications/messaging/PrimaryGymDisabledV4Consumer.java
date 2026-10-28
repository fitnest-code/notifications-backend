package az.fitnest.notifications.messaging;

import az.fitnest.notifications.grpc.IdentityGrpcClient;
import az.fitnest.notifications.model.entity.PrimaryGymDeliveryV4;
import az.fitnest.notifications.repository.PrimaryGymDeliveryV4Repository;
import az.fitnest.notifications.service.NotificationService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PrimaryGymDisabledV4Consumer {
  private final IdentityGrpcClient identity;
  private final NotificationService notifications;
  private final PrimaryGymDeliveryV4Repository delivered;

  @KafkaListener(
      topics = "primary-gym-disabled-v4",
      groupId = "notifications-primary-gym-v4",
      properties = {"spring.json.value.default.type=java.util.Map"})
  public void consume(Map<String, Object> event) {
    String eventId = (String) event.get("eventId");
    var users =
        ((List<?>) event.get("userIds")).stream().map(n -> ((Number) n).longValue()).toList();
    var languages = identity.getUsersLanguageByIds(users);
    var contents = (Map<?, ?>) event.get("contents");
    for (Long userId : users) {
      String id = eventId + ":" + userId;
      if (delivered.existsById(id)) continue;
      var meta = languages.get(userId);
      String language =
          meta == null || meta.language() == null ? "AZ" : meta.language().toUpperCase(Locale.ROOT);
      var content = (Map<?, ?>) contents.getOrDefault(language, null);
      if (content == null) content = (Map<?, ?>) contents.get("AZ");
      notifications.sendPushToUser(
          userId,
          (String) content.get("title"),
          (String) content.get("body"),
          Map.of(
              "type",
              "PRIMARY_GYM_DISABLED",
              "gymId",
              event.get("gymId").toString(),
              "eventId",
              eventId));
      var row = new PrimaryGymDeliveryV4();
      row.setId(id);
      delivered.save(row);
    }
  }
}

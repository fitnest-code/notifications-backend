package az.fitnest.notifications.messaging;

import az.fitnest.notifications.service.EmailService;
import az.fitnest.notifications.service.LsimSmsService;
import az.fitnest.notifications.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@Lazy(false)
@RequiredArgsConstructor
public class NotificationConsumer {

    private final EmailService emailService;
    private final LsimSmsService smsService;
    private final NotificationService notificationService;
    private final az.fitnest.notifications.service.DeviceRegistrationService deviceRegistrationService;
    private final az.fitnest.notifications.repository.DeviceRepository deviceRepository;
    private final az.fitnest.notifications.repository.NotificationRepository notificationRepository;

    @KafkaListener(topics = "user-events", groupId = "notifications-user-events-group", properties = {"spring.json.value.default.type=java.util.Map"})
    @org.springframework.transaction.annotation.Transactional
    public void consumeUserEvent(Map<String, Object> event) {
        String eventType = (String) event.get("eventType");
        Object userIdObj = event.get("userId");
        if (userIdObj == null || eventType == null) {
            return;
        }

        Long userId = parseUserId(userIdObj);
        if (userId == null) {
            return;
        }

        if ("ACCOUNT_DEACTIVATED".equals(eventType)
                || "ACCOUNT_BLOCKED".equals(eventType)
                || "USER_LOGGED_OUT".equals(eventType)) {
            int updated = deviceRegistrationService.disableAllDevicesForUser(userId);
            log.info("Received {}: disabled {} device(s) for userId={}", eventType, updated, userId);
            return;
        }

        if ("USER_HARD_DELETED".equals(eventType)) {
            log.warn("Received USER_HARD_DELETED event for userId: {}. Deleting user devices and notifications.", userId);
            deviceRepository.deleteByUserId(userId);
            notificationRepository.deleteAllByUserId(userId);
        }
    }

    @KafkaListener(topics = "subscription-freeze-events", groupId = "notifications-freeze-group", properties = {"spring.json.value.default.type=java.util.Map"})
    public void consumeFreezeEvent(Map<String, Object> event) {
        String eventType = (String) event.get("eventType");
        Object userIdObj = event.get("userId");
        if (userIdObj == null || eventType == null) {
            return;
        }

        Long userId = parseUserId(userIdObj);
        if (userId == null) {
            return;
        }

        Map<String, String> data = new HashMap<>();
        for (Map.Entry<String, Object> entry : event.entrySet()) {
            if (entry.getValue() != null) {
                data.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }

        switch (eventType) {
            case "subscription_frozen", "freeze_started" ->
                notificationService.sendPushToUser(userId, "FitNest Freeze", "Abunəliyiniz donduruldu", data);
            case "subscription_unfrozen", "freeze_completed" ->
                notificationService.sendPushToUser(userId, "FitNest Freeze", "Abunəliyiniz yenidən aktivdir", data);
            case "freeze_ended_early" ->
                notificationService.sendPushToUser(userId, "FitNest Freeze", "Dondurulma vaxtından əvvəl tamamlandı", data);
            default -> log.debug("Freeze event type: {}", eventType);
        }
    }

    private Long parseUserId(Object obj) {
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        } else if (obj instanceof String) {
            try {
                return Long.parseLong((String) obj);
            } catch (NumberFormatException e) {
                log.error("Failed to parse userId from string: {}", obj);
            }
        }
        return null;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("NotificationConsumer initialized and listening to 'notification-events' topic.");
    }

    @KafkaListener(topics = "notification-events", groupId = "notifications-group", properties = {
            "spring.json.value.default.type=az.fitnest.notifications.messaging.NotificationEvent",
            "spring.json.use.type.headers=false"
    })
    public void consumeNotification(NotificationEvent event) {
        log.info("Consumed notification event: {}, type: {}, recipient: {}",
                event.getEventId(), event.getType(), event.getRecipient());

        try {
            switch (event.getType()) {
                case EMAIL -> handleEmail(event);
                case SMS -> handleSms(event);
                case PUSH -> handlePush(event);
                default -> log.warn("Unknown notification type: {}", event.getType());
            }
        } catch (Exception e) {
            log.error("Failed to process notification event: {}", event.getEventId(), e);
        }
    }

    private void handleEmail(NotificationEvent event) {
        if (event.getTemplateName() != null) {
            Map<String, Object> variables = new HashMap<>();
            if (event.getVariables() != null) {
                variables.putAll(event.getVariables());
            }
            emailService.sendHtmlEmail(event.getRecipient(), event.getSubject(), event.getTemplateName(), variables);
        } else {
            emailService.sendSimpleEmail(event.getRecipient(), event.getSubject(), event.getBody());
        }
    }

    private void handleSms(NotificationEvent event) {
        smsService.sendSms(event.getRecipient(), event.getBody());
    }

    private void handlePush(NotificationEvent event) {
        if (event.getVariables() != null && event.getVariables().containsKey("userId")) {
            Long userId = Long.valueOf(event.getVariables().get("userId"));
            notificationService.sendPushToUser(userId, event.getSubject(), event.getBody(), event.getVariables());
        } else {
            log.warn("Push notification event missing userId: {}", event.getEventId());
        }
    }
}

package az.fitnest.notifications.messaging;

import az.fitnest.notifications.grpc.IdentityGrpcClient;
import az.fitnest.notifications.service.PushDeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Localized pushes for the home-gym lifecycle events published by order-backend
 * on {@code subscription-events} (spec R18 and the push table).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionEventConsumer {

    private static final String HOME_GYM_SELECTED = "HOME_GYM_SELECTED";
    private static final String HOME_GYM_CHANGED = "HOME_GYM_CHANGED";
    private static final String HOME_GYM_REMOVED_BY_SWITCH = "HOME_GYM_REMOVED_BY_SWITCH";
    private static final String HOME_GYM_DISABLED_BY_GYM = "HOME_GYM_DISABLED_BY_GYM";
    private static final String HOME_GYM_ENDED = "HOME_GYM_ENDED";
    private static final String SETTLEMENT_COMPLETED = "SETTLEMENT_COMPLETED";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final MessageSource messageSource;
    private final IdentityGrpcClient identityGrpcClient;
    private final PushDeliveryService pushDeliveryService;

    @KafkaListener(
            topics = "subscription-events",
            groupId = "notifications-subscription-events-group",
            properties = {
                    "spring.json.value.default.type=java.util.Map",
                    "spring.json.use.type.headers=false"
            })
    public void consumeSubscriptionEvent(Map<String, Object> event) {
        if (event == null) {
            return;
        }
        String action = firstNonBlank(asString(event.get("action")), asString(event.get("eventType")));
        Long userId = parseUserId(event.get("userId"));
        if (action == null || userId == null) {
            return;
        }

        Map<String, String> data = payloadData(event, action);

        switch (action) {
            case HOME_GYM_SELECTED, HOME_GYM_CHANGED -> sendSelected(userId, event, data);
            case HOME_GYM_REMOVED_BY_SWITCH -> sendLocalized(
                    userId, event, "home_gym.push.removed.title", "home_gym.push.removed.body", data);
            case HOME_GYM_DISABLED_BY_GYM -> sendGymDisabled(userId, event, data);
            case HOME_GYM_ENDED -> sendLocalized(
                    userId, event, "home_gym.push.ended.title", "home_gym.push.ended.body", data);
            case SETTLEMENT_COMPLETED -> log.debug("Settlement event ignored for user {}", userId);
            default -> log.debug("Subscription event {} ignored for user {}", action, userId);
        }
    }

    private void sendSelected(Long userId, Map<String, Object> event, Map<String, String> data) {
        String language = resolveLanguage(userId);
        Locale locale = locale(language);
        String gymName = gymName(event, locale);
        String validUntil = asString(event.get("validUntil"));

        String title = message("home_gym.push.selected.title", null, locale);
        String body;
        if (validUntil != null) {
            body = message("home_gym.push.selected.body", new Object[]{gymName, formatDate(validUntil)}, locale);
        } else {
            body = message("home_gym.push.selected.body.no_date", new Object[]{gymName}, locale);
        }
        deliver(userId, title, body, data);
    }

    private void sendGymDisabled(Long userId, Map<String, Object> event, Map<String, String> data) {
        String language = resolveLanguage(userId);
        Locale locale = locale(language);

        String title = localizedValue(event.get("title"), language);
        String body = localizedValue(event.get("body"), language);
        if (title == null) {
            title = message("home_gym.push.disabled.title", null, locale);
        }
        if (body == null) {
            body = message("home_gym.push.disabled.body", new Object[]{gymName(event, locale)}, locale);
        }
        deliver(userId, title, body, data);
    }

    private void sendLocalized(Long userId, Map<String, Object> event, String titleKey, String bodyKey,
                               Map<String, String> data) {
        Locale locale = locale(resolveLanguage(userId));
        Object[] args = new Object[]{gymName(event, locale)};
        deliver(userId, message(titleKey, args, locale), message(bodyKey, args, locale), data);
    }

    private void deliver(Long userId, String title, String body, Map<String, String> data) {
        pushDeliveryService.deliverIgnoringSession(userId, title, body, data);
        log.info("Home-gym push sent to user {}: {}", userId, title);
    }

    private String gymName(Map<String, Object> event, Locale locale) {
        String gymName = asString(event.get("gymName"));
        if (gymName != null) {
            return gymName;
        }
        return message("home_gym.push.gym_fallback", null, locale);
    }

    private String resolveLanguage(Long userId) {
        try {
            Map<Long, IdentityGrpcClient.UserLanguageDto> languages =
                    identityGrpcClient.getUsersLanguageByIds(List.of(userId));
            IdentityGrpcClient.UserLanguageDto language = languages.get(userId);
            return normalizeLanguage(language != null ? language.language() : null);
        } catch (Exception e) {
            log.debug("Could not resolve language for user {}: {}", userId, e.getMessage());
            return "AZ";
        }
    }

    private static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "AZ";
        }
        String normalized = language.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "EN", "RU", "AZ" -> normalized;
            default -> "AZ";
        };
    }

    private static Locale locale(String language) {
        return Locale.forLanguageTag(language.toLowerCase(Locale.ROOT));
    }

    private String message(String key, Object[] args, Locale locale) {
        try {
            return messageSource.getMessage(key, args, locale);
        } catch (Exception e) {
            log.warn("Missing notification message key {}: {}", key, e.getMessage());
            return key;
        }
    }

    private static String localizedValue(Object raw, String language) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, String> normalized = new HashMap<>();
        map.forEach((key, value) -> {
            if (key != null && value != null) {
                normalized.put(String.valueOf(key).trim().toUpperCase(Locale.ROOT), String.valueOf(value));
            }
        });
        String value = normalized.get(language);
        if (value == null) {
            value = normalized.get("AZ");
        }
        if (value == null && !normalized.isEmpty()) {
            value = normalized.values().iterator().next();
        }
        return value;
    }

    private static String formatDate(String validUntil) {
        try {
            return OffsetDateTime.parse(validUntil).format(DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return validUntil;
        }
    }

    private static Map<String, String> payloadData(Map<String, Object> event, String action) {
        Map<String, String> data = new HashMap<>();
        data.put("eventType", action);
        event.forEach((key, value) -> {
            if (value == null || value instanceof Map<?, ?> || value instanceof Iterable<?>) {
                return;
            }
            data.put(key, String.valueOf(value));
        });
        return data;
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null ? first : second;
    }

    private static Long parseUserId(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = asString(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

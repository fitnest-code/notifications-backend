package az.fitnest.notifications.service;

import az.fitnest.notifications.model.entity.Notification;

import java.util.HashMap;
import java.util.Map;

/**
 * Copies a push data map onto the inbox row and fills the in-app route for the
 * two screens this release can open. Other event types keep whatever target the
 * caller already set, and stay inbox-only when that target is missing.
 *
 * <p>The input map is never mutated. Callers share one map across many users.
 */
public final class NotificationRouting {

    public static final String TYPE_NEW_GYM = "NEW_GYM";
    public static final String TYPE_CAMPAIGN = "CAMPAIGN";
    public static final String TARGET_GYM = "GYM";
    public static final String TARGET_CAMPAIGN = "CAMPAIGN";

    private static final String SAFE_ID = "[A-Za-z0-9_-]{1,64}";

    private NotificationRouting() {
    }

    public static Map<String, String> enrich(Map<String, String> data) {
        Map<String, String> payload = data == null ? new HashMap<>() : new HashMap<>(data);
        Target target = resolve(payload);
        put(payload, "type", target.type());
        put(payload, "targetType", target.targetType());
        put(payload, "targetId", target.targetId());
        put(payload, "deeplink", target.deeplink());
        if (TARGET_GYM.equals(target.targetType())) {
            put(payload, "gymId", target.targetId());
        }
        return payload;
    }

    public static void apply(Notification notification, Map<String, String> data) {
        Target target = resolve(data);
        notification.setType(target.type());
        notification.setTargetType(target.targetType());
        notification.setTargetId(target.targetId());
        notification.setDeeplink(target.deeplink());
    }

    static Target resolve(Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return Target.empty();
        }

        String type = text(data.get("type"));
        String targetType = text(data.get("targetType"));
        String targetId = safeId(data.get("targetId"));
        String gymId = safeId(data.get("gymId"));

        if (TYPE_NEW_GYM.equals(type)) {
            if (targetType == null) {
                targetType = TARGET_GYM;
            }
            if (targetId == null) {
                targetId = gymId;
            }
        } else if (TYPE_CAMPAIGN.equals(type) && targetType == null) {
            targetType = TARGET_CAMPAIGN;
        }

        String deeplink = safeDeeplink(data.get("deeplink"));
        if (deeplink == null) {
            deeplink = buildDeeplink(targetType, targetId);
        }
        return new Target(type, targetType, targetId, deeplink);
    }

    static String buildDeeplink(String targetType, String targetId) {
        if (targetId == null) {
            return null;
        }
        if (TARGET_GYM.equals(targetType)) {
            return "fitnest://gyms/" + targetId;
        }
        if (TARGET_CAMPAIGN.equals(targetType)) {
            return "fitnest://campaigns/" + targetId;
        }
        return null;
    }

    private static void put(Map<String, String> payload, String key, String value) {
        if (value == null) {
            payload.remove(key);
            return;
        }
        payload.put(key, value);
    }

    private static String text(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            return null;
        }
        return trimmed;
    }

    private static String safeId(String raw) {
        String trimmed = text(raw);
        if (trimmed == null || !trimmed.matches(SAFE_ID)) {
            return null;
        }
        return trimmed;
    }

    private static String safeDeeplink(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("fitnest://") && !trimmed.contains(" ") && trimmed.length() <= 512) {
            return trimmed;
        }
        return null;
    }

    record Target(String type, String targetType, String targetId, String deeplink) {
        static Target empty() {
            return new Target(null, null, null, null);
        }
    }
}

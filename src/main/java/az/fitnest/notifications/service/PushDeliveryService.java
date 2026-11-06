package az.fitnest.notifications.service;

import az.fitnest.notifications.model.entity.Notification;
import az.fitnest.notifications.model.enums.NotificationStatus;

import java.util.List;
import java.util.Map;

/**
 * Low-level FCM send + in-app row updates for push delivery.
 */
public interface PushDeliveryService {

    record MulticastSendResult(int sentCount, int failedCount, List<String> staleTokens, String failureReason) {
    }

    MulticastSendResult sendMulticastInChunks(List<String> tokens, String title, String body, Map<String, String> data);

    void sendToToken(String token, String title, String body, Map<String, String> data);

    void deliverIgnoringSession(Long userId, String title, String body, Map<String, String> data);

    void deliverToUsersIgnoringSession(List<Long> userIds, String title, String body, Map<String, String> data);

    Notification savePendingNotification(Long userId, String title, String body);

    /**
     * Same inbox row as {@link #savePendingNotification(Long, String, String)}, plus the
     * routing fields resolved from {@code data}. A null or empty map leaves the target blank.
     */
    Notification savePendingNotification(Long userId, String title, String body, Map<String, String> data);

    void updateNotificationStatus(Long notificationId, NotificationStatus status, int sentCount, int failedCount, String failureReason);

    void cleanupTokensAndUpdateStatus(Long notificationId, List<String> staleTokens, int sentCount, int failedCount, String failureReason);

    boolean isFirebaseAvailable();
}

package az.fitnest.notifications.service.impl;

import az.fitnest.notifications.model.entity.Notification;
import az.fitnest.notifications.model.enums.NotificationStatus;
import az.fitnest.notifications.repository.DeviceRepository;
import az.fitnest.notifications.repository.NotificationRepository;
import az.fitnest.notifications.service.PushDeliveryService;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
@RequiredArgsConstructor
public class PushDeliveryServiceImpl implements PushDeliveryService {
    private static final Logger logger = LoggerFactory.getLogger(PushDeliveryServiceImpl.class);
    private static final int PUSH_BATCH_SIZE = 50;
    private static final int FCM_MULTICAST_LIMIT = 500;

    private final DeviceRepository deviceRepository;
    private final NotificationRepository notificationRepository;
    private final Optional<FirebaseMessaging> firebaseMessaging;
    private final Executor taskExecutor;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private PushDeliveryServiceImpl self;

    @Override
    public MulticastSendResult sendMulticastInChunks(List<String> tokens, String title, String body, Map<String, String> data) {
        if (firebaseMessaging.isEmpty() || tokens == null || tokens.isEmpty()) {
            return new MulticastSendResult(0, 0, Collections.emptyList(), null);
        }

        int sentCount = 0;
        int failedCount = 0;
        List<String> staleTokens = new ArrayList<>();
        String failureReason = null;
        Map<String, String> payload = data != null ? data : Collections.emptyMap();

        for (int start = 0; start < tokens.size(); start += FCM_MULTICAST_LIMIT) {
            int end = Math.min(start + FCM_MULTICAST_LIMIT, tokens.size());
            List<String> chunk = tokens.subList(start, end);

            MulticastMessage message = MulticastMessage.builder()
                    .addAllTokens(chunk)
                    .setNotification(com.google.firebase.messaging.Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build())
                    .putAllData(payload)
                    .setAndroidConfig(AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .setNotification(AndroidNotification.builder()
                                    .setDefaultSound(true)
                                    .setDefaultVibrateTimings(true)
                                    .build())
                            .build())
                    .setApnsConfig(ApnsConfig.builder()
                            .setAps(Aps.builder()
                                    .setSound("default")
                                    .setBadge(1)
                                    .setContentAvailable(true)
                                    .build())
                            .build())
                    .build();

            try {
                BatchResponse response = firebaseMessaging.get().sendEachForMulticast(message);
                sentCount += response.getSuccessCount();
                failedCount += response.getFailureCount();

                if (response.getFailureCount() > 0) {
                    List<SendResponse> responses = response.getResponses();
                    for (int i = 0; i < responses.size(); i++) {
                        SendResponse sendResponse = responses.get(i);
                        if (!sendResponse.isSuccessful() && isStaleTokenError(fcmErrorCode(sendResponse.getException()))) {
                            staleTokens.add(chunk.get(i));
                        }
                    }
                }
            } catch (FirebaseMessagingException e) {
                failedCount += chunk.size();
                failureReason = e.getMessage();
                logger.error("Firebase messaging error during multicast chunk: {}", e.getMessage(), e);
            } catch (Exception e) {
                failedCount += chunk.size();
                failureReason = e.getMessage();
                logger.error("Unexpected error during multicast chunk: {}", e.getMessage(), e);
            }
        }

        return new MulticastSendResult(sentCount, failedCount, staleTokens, failureReason);
    }

    @Override
    public void sendToToken(String token, String title, String body, Map<String, String> data) {
        if (firebaseMessaging.isEmpty()) {
            logger.warn("Firebase is not initialized. Cannot send push notification to token: {}", maskToken(token));
            return;
        }

        try {
            Map<String, String> payload = data != null ? data : Collections.emptyMap();
            com.google.firebase.messaging.Message message = com.google.firebase.messaging.Message.builder()
                    .setToken(token)
                    .setNotification(com.google.firebase.messaging.Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build())
                    .putAllData(payload)
                    .build();
            firebaseMessaging.get().send(message);
        } catch (FirebaseMessagingException e) {
            if (isStaleTokenError(fcmErrorCode(e))) {
                deviceRepository.deleteByPushToken(token);
            }
        }
    }

    @Override
    @Transactional
    public void deliverIgnoringSession(Long userId, String title, String body, Map<String, String> data) {
        Notification notification = savePendingNotification(userId, title, body);
        Long notificationId = notification.getId();

        if (firebaseMessaging.isEmpty()) {
            updateNotificationStatus(notificationId, NotificationStatus.SENT, 0, 0, "Push service inactive; in-app only");
            return;
        }

        List<String> tokens = deviceRepository.findPushTokensByUserId(userId);
        if (tokens.isEmpty()) {
            updateNotificationStatus(notificationId, NotificationStatus.SENT, 0, 0, "No registered devices; in-app only");
            return;
        }

        Map<String, String> payloadData = data != null ? new java.util.HashMap<>(data) : new java.util.HashMap<>();
        if (notificationId != null) {
            payloadData.put("notificationId", String.valueOf(notificationId));
        }

        MulticastSendResult sendResult = sendMulticastInChunks(tokens, title, body, payloadData);
        cleanupTokensAndUpdateStatus(
                notificationId,
                sendResult.staleTokens(),
                sendResult.sentCount(),
                sendResult.failedCount(),
                sendResult.failureReason());
    }

    @Override
    public void deliverToUsersIgnoringSession(List<Long> userIds, String title, String body, Map<String, String> data) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }

        for (int start = 0; start < userIds.size(); start += PUSH_BATCH_SIZE) {
            int end = Math.min(start + PUSH_BATCH_SIZE, userIds.size());
            List<Long> batch = userIds.subList(start, end);

            List<CompletableFuture<Void>> futures = batch.stream()
                    .map(userId -> CompletableFuture.runAsync(
                            () -> self.deliverIgnoringSession(userId, title, body, data), taskExecutor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }

    @Override
    @Transactional
    public Notification savePendingNotification(Long userId, String title, String body) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setTitle(title);
        notification.setBody(body);
        notification.setRead(false);
        notification.setStatus(NotificationStatus.PENDING);
        return notificationRepository.save(notification);
    }

    @Override
    @Transactional
    public void updateNotificationStatus(Long notificationId, NotificationStatus status, int sentCount, int failedCount, String failureReason) {
        notificationRepository.findById(notificationId).ifPresent(notification -> {
            notification.setStatus(status);
            notification.setSentCount(sentCount);
            notification.setFailedCount(failedCount);
            notification.setFailureReason(failureReason);
        });
    }

    @Override
    @Transactional
    public void cleanupTokensAndUpdateStatus(Long notificationId, List<String> staleTokens, int sentCount, int failedCount, String failureReason) {
        for (String token : staleTokens) {
            deviceRepository.deleteByPushToken(token);
        }

        NotificationStatus finalStatus = NotificationStatus.FAILED;
        if (sentCount > 0 && failedCount == 0) {
            finalStatus = NotificationStatus.SENT;
        } else if (sentCount > 0 && failedCount > 0) {
            finalStatus = NotificationStatus.PARTIAL;
        }

        updateNotificationStatus(notificationId, finalStatus, sentCount, failedCount, failureReason);
    }

    @Override
    public boolean isFirebaseAvailable() {
        return firebaseMessaging.isPresent();
    }

    private static String fcmErrorCode(FirebaseMessagingException e) {
        if (e == null || e.getMessagingErrorCode() == null) {
            return "";
        }
        return e.getMessagingErrorCode().name();
    }

    private static boolean isStaleTokenError(String errorCode) {
        return "UNREGISTERED".equals(errorCode) || "INVALID_ARGUMENT".equals(errorCode);
    }

    private static String maskToken(String token) {
        if (token == null || token.length() <= 8) {
            return "***";
        }
        return token.substring(0, 4) + "***" + token.substring(token.length() - 4);
    }
}

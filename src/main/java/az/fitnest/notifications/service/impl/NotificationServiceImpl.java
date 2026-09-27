package az.fitnest.notifications.service.impl;

import az.fitnest.notifications.dto.NotificationDto;
import az.fitnest.notifications.dto.PushResult;
import az.fitnest.notifications.exception.ResourceNotFoundException;
import az.fitnest.notifications.grpc.IdentityGrpcClient;
import az.fitnest.notifications.mapper.NotificationMapper;
import az.fitnest.notifications.model.entity.Device;
import az.fitnest.notifications.model.entity.Notification;
import az.fitnest.notifications.model.enums.NotificationStatus;
import az.fitnest.notifications.model.enums.Platform;
import az.fitnest.notifications.repository.DeviceRepository;
import az.fitnest.notifications.repository.NotificationRepository;
import az.fitnest.notifications.service.DeviceRegistrationService;
import az.fitnest.notifications.service.LocalizedBroadcastService;
import az.fitnest.notifications.service.LsimSmsService;
import az.fitnest.notifications.service.NewGymNotificationService;
import az.fitnest.notifications.service.NotificationService;
import az.fitnest.notifications.service.PushDeliveryService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {
    private static final Logger logger = LoggerFactory.getLogger(NotificationServiceImpl.class);
    private static final int PUSH_BATCH_SIZE = 50;

    private final LsimSmsService lsimSmsService;
    private final DeviceRepository deviceRepository;
    private final NotificationRepository notificationRepository;
    private final IdentityGrpcClient identityGrpcClient;
    private final java.util.concurrent.Executor taskExecutor;
    private final DeviceRegistrationService deviceRegistrationService;
    private final LocalizedBroadcastService localizedBroadcastService;
    private final NewGymNotificationService newGymNotificationService;
    private final PushDeliveryService pushDeliveryService;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private NotificationServiceImpl self;

    @Override
    public void sendWelcomeSms(String phoneNumber) {
        lsimSmsService.sendSms(phoneNumber, "Xidmətimizə xoş gəlmisiniz!");
    }

    @Override
    public void registerDevice(Long userId, String pushToken, Platform platform) {
        deviceRegistrationService.registerDevice(userId, pushToken, platform);
    }

    @Override
    @Transactional
    public PushResult sendPushToUser(Long userId, String title, String body, Map<String, String> data) {
        if (!pushDeliveryService.isFirebaseAvailable()) {
            logger.warn("Firebase is not initialized. Cannot send push notification to user: {}", userId);
            return PushResult.builder().notificationId(-1L).build();
        }

        try {
            String sessionStatus = identityGrpcClient.getUserSessionStatus(userId);
            if (!"HAVE_SESSIONS".equals(sessionStatus)) {
                return PushResult.builder().notificationId(-1L).build();
            }
        } catch (Exception ignored) {
        }

        Notification notification = pushDeliveryService.savePendingNotification(userId, title, body);
        Long notificationId = notification.getId();

        List<String> tokens = deviceRepository.findPushTokensByUserId(userId);
        if (tokens.isEmpty()) {
            pushDeliveryService.updateNotificationStatus(notificationId, NotificationStatus.FAILED, 0, 0, "No registered devices");
            return PushResult.builder().notificationId(notificationId).build();
        }

        Map<String, String> payloadData = data != null ? new java.util.HashMap<>(data) : new java.util.HashMap<>();
        if (notificationId != null) {
            payloadData.put("notificationId", String.valueOf(notificationId));
        }

        PushDeliveryService.MulticastSendResult sendResult =
                pushDeliveryService.sendMulticastInChunks(tokens, title, body, payloadData);

        pushDeliveryService.cleanupTokensAndUpdateStatus(
                notificationId,
                sendResult.staleTokens(),
                sendResult.sentCount(),
                sendResult.failedCount(),
                sendResult.failureReason());

        return PushResult.builder()
                .notificationId(notificationId)
                .sentCount(sendResult.sentCount())
                .failedCount(sendResult.failedCount())
                .removedTokens(sendResult.staleTokens().size())
                .build();
    }

    @Override
    @Transactional
    public void broadcastPushNotification(String title, String body) {
        if (!pushDeliveryService.isFirebaseAvailable()) {
            logger.warn("Firebase is not initialized. Cannot send broadcast push notification.");
            return;
        }

        List<Long> userIds = deviceRepository.findUserIdsWithActivePushEnabled();
        for (Long userIdForNotification : userIds) {
            pushDeliveryService.savePendingNotification(userIdForNotification, title, body);
        }

        List<String> tokens = deviceRepository.findAllPushTokens();
        if (tokens.isEmpty()) {
            return;
        }

        PushDeliveryService.MulticastSendResult sendResult =
                pushDeliveryService.sendMulticastInChunks(tokens, title, body, Collections.emptyMap());
        if (!sendResult.staleTokens().isEmpty()) {
            for (String staleToken : sendResult.staleTokens()) {
                deviceRepository.deleteByPushToken(staleToken);
            }
            logger.info("Broadcast removed {} stale push token(s)", sendResult.staleTokens().size());
        }
        logger.info("Broadcast finished: sent={}, failed={}, users={}",
                sendResult.sentCount(), sendResult.failedCount(), userIds.size());
    }

    @Override
    public int broadcastLocalizedPushNotification(Map<String, LocalizedPushContent> contentsByLanguage,
                                                  Map<String, String> data) {
        Map<String, LocalizedBroadcastService.LocalizedContent> mapped = new HashMap<>();
        if (contentsByLanguage != null) {
            contentsByLanguage.forEach((lang, content) ->
                    mapped.put(lang, new LocalizedBroadcastService.LocalizedContent(content.title(), content.body())));
        }
        return localizedBroadcastService.broadcast(mapped, data);
    }

    @Override
    public int notifyNewGym(Long gymId, String gymName) {
        return newGymNotificationService.notifyNewGym(gymId, gymName);
    }

    @Override
    public List<PushResult> sendPushToUsers(List<Long> userIds, String title, String body, Map<String, String> data) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<PushResult> results = new ArrayList<>(userIds.size());
        for (int start = 0; start < userIds.size(); start += PUSH_BATCH_SIZE) {
            int end = Math.min(start + PUSH_BATCH_SIZE, userIds.size());
            List<Long> batch = userIds.subList(start, end);

            List<CompletableFuture<PushResult>> futures = batch.stream()
                    .map(userId -> CompletableFuture.supplyAsync(
                            () -> self.sendPushToUser(userId, title, body, data), taskExecutor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            futures.forEach(future -> results.add(future.join()));
        }
        return results;
    }

    @Override
    public void sendPushNotification(String token, String title, String body) {
        sendPushNotification(token, title, body, Collections.emptyMap());
    }

    @Override
    public void sendPushNotification(String token, String title, String body, Map<String, String> data) {
        pushDeliveryService.sendToToken(token, title, body, data);
    }

    @Override
    @Transactional
    public void sendToDevice(Long deviceId, String title, String body) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Cihaz tapılmadı"));

        Notification notification = pushDeliveryService.savePendingNotification(device.getUserId(), title, body);

        if (Boolean.TRUE.equals(device.getNotificationEnabled())) {
            Map<String, String> data = notification.getId() != null
                    ? Map.of("notificationId", String.valueOf(notification.getId()))
                    : Collections.emptyMap();
            pushDeliveryService.sendToToken(device.getPushToken(), title, body, data);
        } else {
            logger.info("Skipping push notification to device {} because notifications are disabled.", deviceId);
        }
    }

    @Override
    public Page<NotificationDto> getUserNotifications(Long userId, Pageable pageable) {
        if (userId == null) {
            return Page.empty(pageable);
        }

        Pageable finalPageable = pageable;
        if (pageable.getSort().isSorted()) {
            finalPageable = org.springframework.data.domain.PageRequest.of(
                    pageable.getPageNumber(),
                    pageable.getPageSize(),
                    org.springframework.data.domain.Sort.by(
                            pageable.getSort().stream()
                                    .map(order -> {
                                        if ("createdAt".equals(order.getProperty())) {
                                            return order.isAscending()
                                                    ? org.springframework.data.domain.Sort.Order.asc("createdDate")
                                                    : org.springframework.data.domain.Sort.Order.desc("createdDate");
                                        }
                                        return order;
                                    })
                                    .collect(Collectors.toList())
                    )
            );
        }

        return notificationRepository.findAllByUserIdOrderByCreatedDateDesc(userId, finalPageable)
                .map(NotificationMapper::toDto);
    }

    @Override
    @Transactional
    public void markNotificationAsRead(Long id, Long userId) {
        Notification notification = notificationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Bildiriş tapılmadı"));
        notification.setRead(true);
        notificationRepository.save(notification);
    }

    @Override
    @Transactional
    public void markAllNotificationsAsRead(Long userId) {
        notificationRepository.markAllAsReadByUserId(userId);
    }

    @Override
    @Transactional
    public void deleteNotification(Long id, Long userId) {
        notificationRepository.deleteByIdAndUserId(id, userId);
    }

    @Override
    @Transactional
    public void deleteAllNotifications(Long userId) {
        notificationRepository.deleteAllByUserId(userId);
    }

    @Override
    public List<Device> getDevicesByUserId(Long userId) {
        return deviceRepository.findAllByUserId(userId);
    }

    @Override
    public void saveDevice(Device device) {
        deviceRepository.save(device);
    }

    @Override
    public int getUnreadCount(Long userId) {
        if (userId == null) {
            return 0;
        }
        return notificationRepository.countByUserIdAndIsReadFalse(userId);
    }
}

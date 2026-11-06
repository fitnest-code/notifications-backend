package az.fitnest.notifications.mapper;

import az.fitnest.notifications.dto.NotificationDto;
import az.fitnest.notifications.dto.NotificationV2Dto;
import az.fitnest.notifications.model.entity.Notification;

public final class NotificationMapper {

    private NotificationMapper() {
    }

    public static NotificationDto toDto(Notification notification) {
        if (notification == null) {
            return null;
        }
        return NotificationDto.builder()
                .id(notification.getId())
                .title(notification.getTitle())
                .body(notification.getBody())
                .isRead(notification.isRead())
                .createdAt(notification.getCreatedDate())
                .build();
    }

    public static NotificationV2Dto toV2Dto(Notification notification) {
        if (notification == null) {
            return null;
        }
        return NotificationV2Dto.builder()
                .id(notification.getId())
                .title(notification.getTitle())
                .body(notification.getBody())
                .isRead(notification.isRead())
                .createdAt(notification.getCreatedDate())
                .type(notification.getType())
                .targetType(notification.getTargetType())
                .targetId(notification.getTargetId())
                .deeplink(notification.getDeeplink())
                .build();
    }
}

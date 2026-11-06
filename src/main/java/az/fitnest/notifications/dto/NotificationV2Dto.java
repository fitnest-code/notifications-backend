package az.fitnest.notifications.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import lombok.Builder;

/**
 * Inbox row for clients that can open a screen from a notification.
 * v1 {@link NotificationDto} is unchanged and does not include these fields.
 */
@Builder
public record NotificationV2Dto(
        Long id,
        String title,
        String body,
        boolean isRead,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime createdAt,
        String type,
        String targetType,
        String targetId,
        String deeplink
) {}

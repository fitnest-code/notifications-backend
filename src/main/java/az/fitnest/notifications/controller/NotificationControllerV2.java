package az.fitnest.notifications.controller;

import az.fitnest.notifications.dto.NotificationV2Dto;
import az.fitnest.notifications.dto.PaginatedResponse;
import az.fitnest.notifications.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbox list that includes the screen a tap should open.
 * Read, delete, and unread-count stay on {@code /api/v1/notifications}.
 */
@RestController
@RequestMapping("/api/v2/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications v2", description = "Bildiriş siyahısı, keçid hədəfi ilə")
@SecurityRequirement(name = "bearerAuth")
public class NotificationControllerV2 {

    private final NotificationService notificationService;

    @Operation(summary = "İstifadəçi bildirişlərini hədəf ilə əldə edin",
            description = "v1 sahələrinə type, targetType, targetId və deeplink əlavə olunur. Köhnə sətirlərdə hədəf null-dır.")
    @GetMapping
    public ResponseEntity<PaginatedResponse<NotificationV2Dto>> getUserNotifications(
            @AuthenticationPrincipal Object principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = principal instanceof Long id ? id : null;
        return ResponseEntity.ok(PaginatedResponse.of(
                notificationService.getUserNotificationsV2(userId, PageRequest.of(page, size))));
    }
}

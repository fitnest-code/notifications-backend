package az.fitnest.notifications.controller;

import az.fitnest.notifications.dto.BlackFridayPushRequest;
import az.fitnest.notifications.dto.BlackFridayPushResponse;
import az.fitnest.notifications.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin v2 notification tools. Black Friday pushes are triggered manually or via ops cron:
 * {@code START} — 10.11 00:00 Asia/Baku; {@code LAST_12H} — 30.11 12:00 Asia/Baku.
 */
@RestController
@RequestMapping("/api/v2/admin/notifications")
@RequiredArgsConstructor
@Tag(name = "Admin Notifications v2", description = "Administrativ bildiriş uçları (v2)")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class NotificationAdminControllerV2 {

    private static final String DEFAULT_BLACK_FRIDAY_DEEPLINK = "fitnest://campaign/black-friday";

    private final NotificationService notificationService;

    @Operation(summary = "Black Friday localized broadcast push (Admin)",
            description = "Sends a localized push to all push-eligible devices. "
                    + "Ops/cron should call START at 10.11 00:00 and LAST_12H at 30.11 12:00 (Asia/Baku).")
    @PostMapping("/black-friday/push")
    public ResponseEntity<BlackFridayPushResponse> pushBlackFriday(
            @Valid @RequestBody BlackFridayPushRequest request) {
        Map<String, NotificationService.LocalizedPushContent> contents = Map.of(
                "AZ", new NotificationService.LocalizedPushContent(request.titleAz(), request.bodyAz()),
                "RU", new NotificationService.LocalizedPushContent(request.titleRu(), request.bodyRu()),
                "EN", new NotificationService.LocalizedPushContent(request.titleEn(), request.bodyEn()));

        Map<String, String> data = new HashMap<>();
        data.put("type", "CAMPAIGN");
        data.put("campaignKind", "BLACK_FRIDAY");
        data.put("pushKind", request.kind().name());
        String deeplink = request.deepLink();
        if (deeplink == null || deeplink.isBlank()) {
            deeplink = DEFAULT_BLACK_FRIDAY_DEEPLINK;
        }
        data.put("deeplink", deeplink.trim());

        int targetUsers = notificationService.broadcastLocalizedPushNotification(contents, data);
        return ResponseEntity.ok(new BlackFridayPushResponse(request.kind(), targetUsers));
    }
}

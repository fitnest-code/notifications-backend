package az.fitnest.notifications.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record BlackFridayPushRequest(
        @NotNull(message = "kind is required")
        BlackFridayPushKind kind,
        @NotBlank String titleAz,
        @NotBlank String titleRu,
        @NotBlank String titleEn,
        @NotBlank String bodyAz,
        @NotBlank String bodyRu,
        @NotBlank String bodyEn,
        String deepLink
) {}

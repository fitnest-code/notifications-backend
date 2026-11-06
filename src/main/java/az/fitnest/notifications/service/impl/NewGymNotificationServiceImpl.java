package az.fitnest.notifications.service.impl;

import az.fitnest.notifications.service.LocalizedBroadcastService;
import az.fitnest.notifications.service.NewGymNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NewGymNotificationServiceImpl implements NewGymNotificationService {

    private final LocalizedBroadcastService localizedBroadcastService;

    @Override
    public int notifyNewGym(Long gymId, String gymName) {
        String name = gymName != null ? gymName.trim() : "";
        String gymIdStr = gymId != null ? gymId.toString() : "";

        Map<String, LocalizedBroadcastService.LocalizedContent> contents = Map.of(
                "AZ", new LocalizedBroadcastService.LocalizedContent(
                        "Yeni idman zalı əlavə edildi",
                        String.format("Yeni tərəfdaşımız %s artıq FitNest-dədir. Ətraflı məlumat üçün toxunun.", name)),
                "EN", new LocalizedBroadcastService.LocalizedContent(
                        "New gym added",
                        String.format("Our new partner %s is now on FitNest. Tap to learn more.", name)),
                "RU", new LocalizedBroadcastService.LocalizedContent(
                        "Добавлен новый зал",
                        String.format("Наш новый партнёр %s теперь в FitNest. Нажмите, чтобы узнать больше.", name))
        );

        Map<String, String> data = new HashMap<>();
        data.put("type", "NEW_GYM");
        if (!gymIdStr.isEmpty()) {
            data.put("gymId", gymIdStr);
            data.put("targetType", "GYM");
            data.put("targetId", gymIdStr);
            data.put("deeplink", "fitnest://gyms/" + gymIdStr);
        }

        return localizedBroadcastService.broadcast(contents, data);
    }
}

package az.fitnest.notifications.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "primary_gym_delivery_v4")
@Getter
@Setter
@NoArgsConstructor
public class PrimaryGymDeliveryV4 {
  @Id private String id;
}

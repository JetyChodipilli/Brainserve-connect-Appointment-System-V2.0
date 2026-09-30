package com.brainserve.appointment.configuration.infrastructure;

import com.brainserve.appointment.configuration.domain.SystemSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SystemSettingRepository extends JpaRepository<SystemSetting, UUID> {
    Optional<SystemSetting> findByKey(String key);
}

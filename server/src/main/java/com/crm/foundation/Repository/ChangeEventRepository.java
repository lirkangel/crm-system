package com.crm.foundation.Repository;

import com.crm.foundation.Domain.ChangeEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChangeEventRepository extends JpaRepository<ChangeEvent, UUID> {
}

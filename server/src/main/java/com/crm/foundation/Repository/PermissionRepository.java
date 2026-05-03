package com.crm.foundation.Repository;

import com.crm.foundation.Domain.Permissions;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface PermissionRepository extends JpaRepository<Permissions, UUID> {
    @Query("select * from permissions p where p.key = ?1 limit 1")
    Optional<Permissions> findByKey(String key);
}

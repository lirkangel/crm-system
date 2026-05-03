package com.crm.foundation.Repository;

import com.crm.foundation.Domain.Permissions;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PermissionRepository extends JpaRepository<Permissions, UUID> {
    @Query("select p from Permissions p where p.key = :key")
    Optional<Permissions> findByKey(@Param("key") String key);
}

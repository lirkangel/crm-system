package com.crm.foundation.Repository;

import com.crm.foundation.Domain.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {
    @Query("select p from Permission p where p.key = :key")
    Optional<Permission> findByKey(@Param("key") String key);
}

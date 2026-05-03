package com.crm.foundation.Repository;

import com.crm.foundation.Domain.Roles;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Roles, UUID> {

    @Query("select r from Roles r where r.nameI18n like concat('%', :name, '%')")
    List<Roles> findByName(@Param("name") String name);
}

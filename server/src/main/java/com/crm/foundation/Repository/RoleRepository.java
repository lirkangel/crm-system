package com.crm.foundation.Repository;

import com.crm.foundation.Domain.Roles;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Roles, UUID> {

    @Query("select * from roles r where r.name_i18n like %?1")
    List<Roles> findByName(String name);
}

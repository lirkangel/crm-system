package com.crm.foundation.Repository;

import com.crm.foundation.Domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    @Query(
        value = """
            select distinct r.*
            from roles r
            where exists (
                select 1
                from jsonb_each_text(r.name_i18n) as localized_name(locale, value)
                where localized_name.value ilike concat('%', :name, '%')
            )
            """,
        nativeQuery = true)
    List<Role> findByName(@Param("name") String name);
}

package com.crm.foundation.Service.Impl;

import com.crm.foundation.Domain.Permission;
import com.crm.foundation.Repository.PermissionRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PermissionServiceImplTest {
    @Mock
    private PermissionRepository permissionRepository;

    @InjectMocks
    private PermissionServiceImpl permissionService;

    @Test
    void findAll_delegatesToRepository() {
        Permission read = new Permission();
        read.setKey("users.read");
        Permission write = new Permission();
        write.setKey("users.write");
        when(permissionRepository.findAll()).thenReturn(List.of(read, write));

        List<Permission> result = permissionService.findAll();

        assertThat(result).containsExactly(read, write);
        verify(permissionRepository).findAll();
    }

    @Test
    void findById_returnsPermissionWhenRepositoryFindsOne() {
        UUID id = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000007"));
        Permission permission = new Permission();
        permission.setKey("roles.manage");
        when(permissionRepository.findById(id)).thenReturn(Optional.of(permission));

        Optional<Permission> result = permissionService.findById(id);

        assertThat(result).isPresent();
        assertThat(result.get()).isSameAs(permission);
        verify(permissionRepository).findById(id);
    }

    @Test
    void findById_returnsEmptyWhenMissing() {
        UUID id = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000099"));
        when(permissionRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(permissionService.findById(id)).isEmpty();
        verify(permissionRepository).findById(id);
    }

    @Test
    void findByKey_returnsPermissionWhenRepositoryFindsOne() {
        Permission permission = new Permission();
        permission.setKey("billing.view");
        when(permissionRepository.findByKey("billing.view")).thenReturn(Optional.of(permission));

        Optional<Permission> result = permissionService.findByKey("billing.view");

        assertThat(result).isPresent();
        assertThat(result.get()).isSameAs(permission);
        verify(permissionRepository).findByKey("billing.view");
    }
}

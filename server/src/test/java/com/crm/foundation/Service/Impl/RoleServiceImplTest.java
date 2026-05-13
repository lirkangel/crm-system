package com.crm.foundation.Service.Impl;

import com.crm.foundation.Domain.Role;
import com.crm.foundation.Repository.RoleRepository;
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
public class RoleServiceImplTest {
    @Mock
    private RoleRepository roleRepository;

    @InjectMocks
    private RoleServiceImpl roleService;

    @Test
    void findById_returnsRoleWhenRepositoryFindsOne() {
        UUID id = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000007"));
        Role roles = new Role();
        roles.setId(id);
        roles.setCode("alice");
        when(roleRepository.findById(id)).thenReturn(Optional.of(roles));

        Optional<Role> result = roleService.findById(id);

        assertThat(result).isPresent();
        assertThat(result.get()).isSameAs(roles);
        verify(roleRepository).findById(id);
    }

    @Test
    void findByLikeName_returnRolesWhenRepositoryFindLike() {
        UUID id1 = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000007"));
        Role roles1 = new Role();
        roles1.setId(id1);
        roles1.setCode("administrator");
        when(roleRepository.findByName("admin")).thenReturn(List.of(roles1));

        List<Role> result = roleService.findByName("admin");

        assertThat(result).isNotEmpty();
        assertThat(result.getFirst().getCode()).isSameAs(roles1.getCode());
    }

    @Test
    void findById_returnsEmptyWhenMissing() {
        UUID id = Objects.requireNonNull(UUID.fromString("00000000-0000-0000-0000-000000000099"));
        when(roleRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(roleService.findById(id)).isEmpty();
        verify(roleRepository).findById(id);
    }

    @Test
    void findAll_delegatesToRepository() {
        Role a = new Role();
        a.setCode("a");
        Role b = new Role();
        b.setCode("b");
        when(roleRepository.findAll()).thenReturn(List.of(a, b));

        List<Role> result = roleService.findAll();

        assertThat(result).containsExactly(a, b);
        verify(roleRepository).findAll();
    }
}

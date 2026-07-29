package com.multitenant.tenant;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantUserRepository extends JpaRepository<TenantUser, Long> {

	Optional<TenantUser> findByEmailIgnoreCase(String email);
}

package com.marketflow.identity.persistence;

import com.marketflow.identity.domain.User;
import com.marketflow.platform.security.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmail(@Param("email") String email);

    boolean existsByEmailIgnoreCase(String email);

    @Query("select count(u) > 0 from User u join u.roles r where r = :role")
    boolean existsWithRole(@Param("role") Role role);

    @Query("""
            select u from User u
            where (:q is null or lower(u.email) like lower(concat('%', cast(:q as string), '%'))
                   or lower(u.fullName) like lower(concat('%', cast(:q as string), '%')))
            """)
    Page<User> search(@Param("q") String q, Pageable pageable);
}

package com.grocery.identity.repository;

import com.grocery.identity.entity.UserAccount;
import com.grocery.identity.entity.UserStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByEmail(String email);

    /**
     * Row lock (SELECT … FOR UPDATE): password reset requests for one account run one after the other. As the first read
     * of a transaction it also starts no snapshot, so the reads after it see what the request before it committed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserAccount u where u.email = :email")
    Optional<UserAccount> findByEmailForUpdate(@Param("email") String email);

    boolean existsByEmail(String email);

    @Query("select count(u) > 0 from UserAccount u join u.roles r where r = :role")
    boolean existsWithRole(@Param("role") String role);

    /**
     * The admin search's filters, shared by the list, its count and its export (alias {@code u}; the role join makes
     * a user with several roles several rows, so every select over it is {@code distinct u}).
     */
    String ADMIN_SEARCH = """
            from UserAccount u left join u.roles r
            where (:status is null or u.status = :status)
              and (:role is null or r = :role)
              and (:query is null or lower(u.email) like :query or lower(u.fullName) like :query)
            """;

    /** Admin search; every filter is optional. {@code query} must already be lower-cased with % wildcards. */
    @Query(value = "select distinct u " + ADMIN_SEARCH, countQuery = "select count(distinct u) " + ADMIN_SEARCH)
    Page<UserAccount> search(@Param("query") String query, @Param("role") String role,
                             @Param("status") UserStatus status, Pageable pageable);
}

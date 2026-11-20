package com.grocery.identity.repository;

import com.grocery.identity.entity.SigningKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SigningKeyRepository extends JpaRepository<SigningKey, String> {

    List<SigningKey> findByActiveTrueOrderByCreatedAtAsc();
}

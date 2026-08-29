package com.meowpay.cat;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface CatRepository extends JpaRepository<Cat, UUID> {

    Optional<Cat> findByUsername(String username);

    boolean existsByUsername(String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cat c where c.id = :id")
    Optional<Cat> lockById(@Param("id") UUID id);
}

package com.tsb.strategy;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A named strategy owned by a user — the mutable shell around an immutable
 * chain of {@link StrategyVersion}s. Note the FK style used across this
 * feature: plain {@code Long} ids, not {@code @ManyToOne} object graphs.
 * With open-in-view off, lazy association traversal is a landmine; explicit
 * ids + explicit repository queries keep every DB access visible (the
 * aggregate-reference pattern).
 */
@Entity
@Table(name = "strategies")
public class Strategy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private String name;

    private Instant createdAt;

    private Instant updatedAt;

    protected Strategy() {
    }

    public Strategy(Long userId, String name) {
        this.userId = userId;
        this.name = name;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
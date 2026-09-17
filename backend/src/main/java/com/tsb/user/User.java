package com.tsb.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Platform user. This is the pre-auth entity grown to carry credentials —
 * id, username and createdAt are unchanged, so everything that already
 * reads them keeps working.
 *
 * An account holds at most one of each credential and at least one of them:
 * passwordHash for email sign-in, googleSub for Google. The exception is the
 * seeded 'dev' row from V3, which has neither and therefore cannot sign in
 * at all — it exists only so strategies created before the auth phase keep
 * a valid owner.
 *
 * googleSub stores Google's `sub` claim, never the email. Google addresses
 * can be reassigned between people; subjects cannot.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(length = 255)
    private String email;

    /** BCrypt. Null for a Google-only account. */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "google_sub", length = 255)
    private String googleSub;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "avatar_url",length = 512)
    private String avatarUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
        // for JPA
    }

    private User(String username, String email, String passwordHash,
                 String googleSub, String displayName, String avatarUrl) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.googleSub = googleSub;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
    }

    public static User withPassword(String username, String email, String passwordHash) {
        return new User(username, email, passwordHash, null, null, null);
    }

    public static User withGoogle(String username, String email, String googleSub,
                                  String displayName, String avatarUrl) {
        return new User(username, email, null, googleSub, displayName, avatarUrl);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * Attaches a Google identity to an account that already exists. Only ever
     * called after the owner has proved they hold the password — see
     * AuthService.signInWithGoogle.
     */
    public void linkGoogle(String googleSub, String displayName, String avatarUrl) {
        this.googleSub = googleSub;
        if (this.displayName == null) this.displayName = displayName;
        if (this.avatarUrl == null) this.avatarUrl = avatarUrl;
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getGoogleSub() { return googleSub; }
    public String getDisplayName() { return displayName; }
    public String getAvatarUrl() { return avatarUrl; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
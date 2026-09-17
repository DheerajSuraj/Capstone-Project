# TSB auth — backend

Twelve Java files plus one migration. Written against Spring Boot 4 /
Spring Security 7, and equally valid on Boot 3 / Security 6.

## 0. Before anything: the package name

Every file declares `package com.tsb.auth;` or `package com.tsb.config;`.
**If your base package is different, find-and-replace `com.tsb` with yours**
before the first compile. I could not read your source tree from here, so
this is a guess based on the project name.

Files land at:

```
src/main/java/com/tsb/auth/       AuthController, AuthService, TokenService, …
src/main/java/com/tsb/config/     SecurityConfig
src/main/resources/db/migration/  V4__auth.sql
```

If you already have a `User` entity or a `UserRepository` anywhere, **do not
add these alongside** — merge instead. Two entities mapped to `users` will
start and then behave strangely.

## 1. Dependencies

Maven:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

Gradle:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-security")
implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
implementation("org.springframework.boot:spring-boot-starter-validation")
```

No JWT library. `oauth2-resource-server` brings Nimbus, which both signs our
access tokens and verifies Google's. It also brings a correct Bearer-token
filter, which is why there is no hand-written `JwtAuthFilter` here.

## 2. Configuration

`application.yml`:

```yaml
tsb:
  auth:
    jwt-secret: ${TSB_JWT_SECRET}
    access-token-ttl: 15m
    refresh-token-ttl: 30d
    cookie-secure: false          # true in production, always
    google-client-id: ${TSB_GOOGLE_CLIENT_ID:}
    max-login-attempts: 5
    login-attempt-window: 15m
    allowed-origins: http://localhost:5173
```

Generate the secret once and put it in your environment, never in the repo:

```powershell
# PowerShell
[Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Max 256 }))
```

The app refuses to start if it is missing or under 32 bytes. That is
deliberate — a JWT secret that defaults to something is a JWT secret an
attacker already has.

`google-client-id` is the **same** Web client ID the frontend uses. Leave it
empty and Google sign-in returns a clean "not configured" error while
email/password keeps working, so you can build and demo without touching
the Google console.

## 3. Scheduling

`LoginRateLimiter` has an `@Scheduled` eviction sweep. You already run a
five-minute candle sync, so `@EnableScheduling` is presumably on your
application class. If it is not, add it, or the map grows unbounded.

## 4. Run the migration

```powershell
./mvnw spring-boot:run
```

Flyway applies `V4__auth.sql` on startup. Then confirm:

```powershell
docker exec tsb-postgres psql -U tsb -d tsb -c "\d users"
docker exec tsb-postgres psql -U tsb -d tsb -c "select id, username, email, password_hash is not null as has_pw from users"
```

You should see the seeded `dev` row with no email and no password. It keeps
owning your existing strategies and can no longer sign in.

## 5. Replace the hardcoded user

This is the part that is not in these files, because it is in yours.

V3 seeded `dev` with the comment *"Until the auth phase every request acts as
this user"*, so somewhere in your strategy service there is a `1L`, or a
`findByUsername("dev")`, or a constant. Find it:

```powershell
Select-String -Path src\main\java -Pattern "getReferenceById\(1|\b1L\b|""dev""" -Recurse
```

Inject `CurrentUser` and replace it:

```java
private final CurrentUser currentUser;

// was: strategy.setUserId(1L);
strategy.setUserId(currentUser.requireId());

// when loading something owned:
Strategy s = strategies.findById(id).orElseThrow(...);
currentUser.requireOwns(s.getUserId());
```

`requireOwns` throws the same error for "not found" and "not yours", so the
API cannot be used to work out which strategy ids exist.

**Expect your existing strategies to vanish from the UI after this.** They
belong to `dev`, and you will be signed in as someone else. They are still in
the table. If you want them, reassign once:

```sql
UPDATE strategies SET user_id = <your new id> WHERE user_id = 1;
```

## 6. Try it

```powershell
# sign up
curl -X POST http://localhost:8080/api/auth/signup -H "Content-Type: application/json" -d '{\"username\":\"juan_c\",\"email\":\"juan@example.com\",\"password\":\"correct horse battery\"}'

# username check
curl "http://localhost:8080/api/auth/username-available?u=juan_c"

# sign in, keeping the cookie
curl -c cookies.txt -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{\"identifier\":\"juan_c\",\"password\":\"correct horse battery\"}'

# refresh using it
curl -b cookies.txt -X POST http://localhost:8080/api/auth/refresh
```

Run the refresh call **twice with the same cookie file edited back** and the
second one 401s with the whole family revoked. That is reuse detection
working, not a bug.

## What to say when you are asked about this

Four things in here are worth a sentence each in Chapter 3, because they are
decisions rather than defaults:

**Look-ahead has a cousin here: timing.** `signIn` runs BCrypt against a
fixed dummy hash even when no such user exists. Without it, "unknown email"
returns in 1 ms and "wrong password" in 50 ms, and that difference alone
enumerates your users. Same principle as the engine — make the wrong answer
structurally unavailable rather than checking for it.

**Refresh tokens rotate and are grouped into families.** Presenting a spent
token revokes the whole family, because a leak and a legitimate replay look
identical from the server. This is the OAuth 2.0 browser-based-apps BCP
rule, not something invented here.

**Google is keyed on `sub`, never on email.** Google addresses can be
reassigned between people; the subject claim cannot. And a Google sign-in
whose email matches an existing *password* account is refused rather than
merged — otherwise whoever controls that mailbox inherits the TSB account.

**Case-insensitive usernames.** V3's plain `UNIQUE` would have allowed `juan`
and `Juan` side by side on a leaderboard. V4 adds a unique index on
`LOWER(username)`.

## Known limitations, stated rather than hidden

- **Rate limiting is in-memory.** It resets on restart and is not shared
  between instances. Fine for one instance; for anything scaled it belongs
  in Redis. The class is small and the interface is one method.
- **No email verification.** Nothing stops one person making several
  accounts, which matters once competitions rank people. Either add
  verification, or cap entries per user per competition in the competition
  rules — but decide, because it is the first thing an examiner will ask
  about leaderboard integrity.
- **No password reset.** The `/forgot-password` link in the UI goes nowhere
  yet. It needs an email sender, which is the same infrastructure decision
  as verification above.
- **The breached-password list is 27 entries.** The real fix is the Have I
  Been Pwned range API — SHA-1 the password, send the first five hex
  characters, compare suffixes locally. The password never leaves the
  server. Roughly twenty lines.

## File map

```
auth/
  User.java                  entity: username + at most one credential of each kind
  UserRepository.java        case-insensitive lookups matching the V4 indexes
  RefreshToken.java          stored as a SHA-256 hash, never raw
  RefreshTokenRepository.java
  AuthProperties.java        tsb.auth.* binding, fails fast on a weak secret
  TokenService.java          access / refresh / pending tokens, rotation, reuse detection
  GoogleTokenVerifier.java   signature, issuer, audience, email_verified
  GoogleIdentity.java        a verified Google identity; only the verifier makes one
  PasswordPolicy.java        length over composition, per NIST SP 800-63B
  UsernameService.java       rules, availability, suggestions
  LoginRateLimiter.java      per-identifier attempt cap
  AuthService.java           the decisions
  AuthController.java        HTTP and cookies
  AuthException.java         every failure with the code the frontend switches on
  AuthExceptionHandler.java  scoped to AuthController only
  AuthDtos.java              wire shapes, mirroring api.ts
  CurrentUser.java           the seam that replaces hardcoded user 1
config/
  SecurityConfig.java        filter chain, encoders, CORS
resources/db/migration/
  V4__auth.sql
```

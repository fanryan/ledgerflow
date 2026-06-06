# Authentication

This document explains the current LedgerFlow authentication implementation as it exists today. It is written for resume and interview preparation, so it focuses on how the code works, what decisions were made, and what is still planned.

## 1. Current Auth Scope

### Implemented

The Spring Boot API currently supports:

- Public `GET /health`.
- Public `POST /auth/login`.
- Public `POST /auth/refresh`.
- Protected `GET /auth/me`.
- PostgreSQL-backed `users` table.
- Seeded local admin user.
- BCrypt password verification.
- JWT access token generation.
- JWT refresh token generation.
- JWT refresh token validation for the stateless refresh endpoint.
- JWT parsing and validation for incoming requests.
- Spring Security `SecurityContext` population from a valid bearer token.
- Global error handling for invalid credentials.
- Global error handling for invalid refresh tokens.
- Auth flow tests covering login, refresh, invalid credentials, invalid tokens, and `/auth/me`.

The current local admin credentials are:

```text
email: admin@ledgerflow.local
password: password
```

### Not Implemented Yet

These are planned but not currently implemented:

- Refresh token rotation with persistence.
- Refresh token revocation.
- Logout.
- Token persistence or refresh-token database tracking.
- Role-based method annotations such as `@PreAuthorize`.
- Full error contract coverage for every exception type.
- Production secret management.

### Public Endpoints

Configured in `services/ledger-api/src/main/java/com/fanryan/ledgerflow/security/SecurityConfig.java`:

```text
GET  /health
POST /auth/login
POST /auth/refresh
```

### Protected By Default

Every other endpoint requires authentication by default:

```text
anyRequest().authenticated()
```

That means `GET /auth/me` requires:

```http
Authorization: Bearer <access_token>
```

## 2. Runtime Flow

### App Startup

When running:

```bash
cd services/ledger-api
gradle bootRun
```

Spring Boot starts from:

```text
services/ledger-api/src/main/java/com/fanryan/ledgerflow/LedgerflowApplication.java
```

Startup flow:

```text
Gradle bootRun
  |
  v
LedgerflowApplication.main()
  |
  v
SpringApplication.run(...)
  |
  +--> loads application.yml
  +--> creates Spring beans
  +--> connects to PostgreSQL
  +--> runs Flyway migrations
  +--> builds Spring Security filter chain
  +--> starts embedded Tomcat on port 8080
```

### SecurityConfig

`SecurityConfig.java` defines the main HTTP security rules:

- CSRF disabled.
- Form login disabled.
- HTTP Basic disabled.
- Stateless session policy.
- `/health`, `/auth/login`, and `/auth/refresh` are public.
- All other endpoints require authentication.
- `JwtAuthenticationFilter` runs before Spring's `UsernamePasswordAuthenticationFilter`.
- A `BCryptPasswordEncoder` bean is available for password checks.

This makes the API behave like a stateless JSON API rather than a browser session application.

### JwtAuthenticationFilter in the Pipeline

The filter is registered here:

```text
SecurityConfig.java
```

It runs before:

```text
UsernamePasswordAuthenticationFilter
```

Its job is to inspect each request for:

```http
Authorization: Bearer <token>
```

If a valid JWT exists, the filter stores authentication in Spring Security's `SecurityContextHolder`.

### application.yml to JwtProperties

JWT settings live in:

```text
services/ledger-api/src/main/resources/application.yml
```

```yaml
ledgerflow:
  jwt:
    secret: "local-development-secret-key-change-later-please-make-this-long"
    access-token-ttl-minutes: 60
    refresh-token-ttl-days: 7
```

They bind into:

```text
services/ledger-api/src/main/java/com/fanryan/ledgerflow/auth/JwtProperties.java
```

using:

```java
@ConfigurationProperties(prefix = "ledgerflow.jwt")
```

`LedgerflowApplication.java` enables this binding with:

```java
@EnableConfigurationProperties(JwtProperties.class)
```

## 3. Login Flow

### Request

`POST /auth/login`

```json
{
  "email": "admin@ledgerflow.local",
  "password": "password"
}
```

### Step-by-Step

1. `AuthController.login(...)` receives the JSON request.
2. Spring converts the JSON body into `LoginRequest`.
3. `AuthController` calls `AuthService.login(...)`.
4. `AuthService` calls `UserRepository.findByEmail(...)`.
5. Spring Data JDBC queries the `users` table.
6. If no user exists, `InvalidCredentialsException` is thrown.
7. If a user exists, `PasswordEncoder.matches(...)` checks the raw password against the BCrypt hash.
8. If the password does not match, `InvalidCredentialsException` is thrown.
9. If the password matches, `JwtService` generates an access token and refresh token.
10. `AuthService` returns `LoginResponse`.
11. Spring serializes `LoginResponse` as JSON.

### Diagram

```text
Client
  |
  | POST /auth/login
  | {"email":"admin@ledgerflow.local","password":"password"}
  v
AuthController
  |
  | LoginRequest
  v
AuthService
  |
  +--> UserRepository.findByEmail(email)
  |       |
  |       v
  |     PostgreSQL users table
  |
  +--> PasswordEncoder.matches(rawPassword, passwordHash)
  |
  +--> JwtService.generateAccessToken(user)
  |
  +--> JwtService.generateRefreshToken(user)
  |
  v
LoginResponse
  |
  v
{"accessToken":"...","refreshToken":"..."}
```

### Failure Path

Bad email or password throws:

```text
InvalidCredentialsException
```

`GlobalExceptionHandler` maps this to:

```text
HTTP 401
```

with:

```json
{
  "errorCode": "INVALID_CREDENTIALS",
  "message": "Invalid email or password",
  "requestId": "...",
  "timestamp": "..."
}
```

## 4. Refresh Flow

### Request

`POST /auth/refresh`

```json
{
  "refreshToken": "<refresh_token>"
}
```

### Step-by-Step

1. `AuthController.refresh(...)` receives the JSON request.
2. Spring converts the JSON body into `RefreshTokenRequest`.
3. `AuthController` calls `AuthService.refresh(...)`.
4. `AuthService` asks `JwtService` to parse the refresh token and extract the user id from the `sub` claim.
5. If parsing fails because the token is expired, malformed, or signed incorrectly, `InvalidTokenException` is thrown.
6. If parsing succeeds, `AuthService` loads the user by id through `UserRepository.findById(...)`.
7. If the user no longer exists, `InvalidTokenException` is thrown.
8. If the user exists, `JwtService` generates a new access token and a new refresh token.
9. `AuthService` returns `LoginResponse`.

### Diagram

```text
Client
  |
  | POST /auth/refresh
  | {"refreshToken":"..."}
  v
AuthController
  |
  | RefreshTokenRequest
  v
AuthService
  |
  +--> JwtService.getUserId(refreshToken)
  |       |
  |       +--> verify signature
  |       +--> verify expiry
  |       +--> read sub claim
  |
  +--> UserRepository.findById(userId)
  |       |
  |       v
  |     PostgreSQL users table
  |
  +--> JwtService.generateAccessToken(user)
  |
  +--> JwtService.generateRefreshToken(user)
  |
  v
LoginResponse
  |
  v
{"accessToken":"...","refreshToken":"..."}
```

### Failure Path

Bad refresh tokens throw:

```text
InvalidTokenException
```

`GlobalExceptionHandler` maps this to:

```text
HTTP 401
```

with:

```json
{
  "errorCode": "INVALID_TOKEN",
  "message": "Invalid or expired token",
  "requestId": "...",
  "timestamp": "..."
}
```

## 5. JWT Validation Flow

For a protected endpoint, clients send:

```http
Authorization: Bearer <token>
```

### Step-by-Step

1. Request enters the Spring Security filter chain.
2. `JwtAuthenticationFilter` reads the `Authorization` header.
3. If the header is missing or does not start with `Bearer `, the request continues unauthenticated.
4. If a bearer token exists, the filter extracts the token string.
5. `JwtService.getUserId(token)` parses and verifies the token.
6. `JwtService.getRole(token)` reads the `role` claim.
7. The filter creates a `UsernamePasswordAuthenticationToken`.
8. The principal is the user UUID from the JWT `sub` claim.
9. The authority is `ROLE_` plus the JWT role claim, for example `ROLE_ADMIN`.
10. The filter stores this authentication object in `SecurityContextHolder`.
11. Spring Security authorization rules evaluate the request.
12. If the endpoint requires authentication and the context is populated, the request reaches the controller.

### Diagram

```text
Client
  |
  | GET /auth/me
  | Authorization: Bearer <access_token>
  v
Spring Security Filter Chain
  |
  v
JwtAuthenticationFilter
  |
  +--> read Authorization header
  +--> extract Bearer token
  +--> JwtService.parseToken(token)
        |
        +--> verify signature
        +--> verify expiry through JJWT parsing
        +--> read sub
        +--> read role
  |
  +--> UsernamePasswordAuthenticationToken
        principal: user UUID
        authorities: ROLE_ADMIN
  |
  +--> SecurityContextHolder.setAuthentication(...)
  |
  v
Authorization rules
  |
  v
AuthController.me(...)
  |
  v
CurrentUserResponse
```

### Missing Token

If the header is missing:

```text
JwtAuthenticationFilter continues without authentication.
```

For public endpoints, this is fine.

For protected endpoints, Spring Security blocks the request.

### Invalid Token

If the token is invalid, expired, malformed, or has bad claims:

```text
JwtAuthenticationFilter clears SecurityContextHolder.
```

The request continues unauthenticated, and protected endpoints are blocked by Spring Security.

## 6. File-by-File Explanation

### `AuthController.java`

Path:

```text
services/ledger-api/src/main/java/com/fanryan/ledgerflow/auth/AuthController.java
```

Defines the auth HTTP endpoints:

- `POST /auth/login`
- `POST /auth/refresh`
- `GET /auth/me`

`login(...)` delegates to `AuthService`.

`refresh(...)` delegates to `AuthService` to validate a refresh token and issue a new token pair.

`me(...)` reads the current `Authentication` from Spring Security and returns the user id and role.

### `AuthService.java`

Contains login business logic:

- Load user by email.
- Verify password with `PasswordEncoder`.
- Generate access and refresh tokens with `JwtService`.
- Validate refresh tokens.
- Load the token subject user from the database.
- Generate a replacement access and refresh token pair.

This is where authentication rules live, not in the controller.

### `CurrentUserResponse.java`

DTO returned by:

```text
GET /auth/me
```

It contains:

- `userId`
- `role`

### `InvalidCredentialsException.java`

Specific exception for invalid login attempts.

It avoids throwing generic `IllegalArgumentException` and lets `GlobalExceptionHandler` map failed login to a clean `401`.

### `InvalidTokenException.java`

Specific exception for invalid refresh token attempts.

It lets `GlobalExceptionHandler` map bad, expired, malformed, or unusable refresh tokens to a clean `401`.

### `JwtProperties.java`

Maps `ledgerflow.jwt` values from `application.yml` into Java fields:

- `secret`
- `accessTokenTtlMinutes`
- `refreshTokenTtlDays`

### `JwtService.java`

Owns JWT creation and parsing.

It currently:

- Builds a signing key from the configured secret.
- Generates access tokens.
- Generates refresh tokens.
- Parses signed JWT claims.
- Extracts user id from `sub`.
- Extracts role from `role`.

Generated claims:

- `sub`: user id
- `role`: user role
- `iat`: issued at
- `exp`: expiration time
- `jti`: token id

### `LoginRequest.java`

Request DTO for `POST /auth/login`.

Fields:

- `email`
- `password`

### `LoginResponse.java`

Response DTO for successful login.

Fields:

- `accessToken`
- `refreshToken`

### `RefreshTokenRequest.java`

Request DTO for `POST /auth/refresh`.

Fields:

- `refreshToken`

### `User.java`

Spring Data JDBC model mapped to:

```text
users
```

Fields:

- `id`
- `email`
- `passwordHash`
- `role`
- `createdAt`
- `updatedAt`

### `UserRepository.java`

Spring Data JDBC repository for `User`.

Extends:

```java
CrudRepository<User, UUID>
```

Defines:

```java
Optional<User> findByEmail(String email);
```

Spring generates the query implementation from the method name.

### `UserRole.java`

Enum for supported roles:

- `USER`
- `ADMIN`
- `OPERATOR`

### `SecurityConfig.java`

Defines Spring Security behavior:

- Public `/health`.
- Public `/auth/login`.
- Public `/auth/refresh`.
- Everything else authenticated.
- Stateless sessions.
- CSRF disabled.
- Form login disabled.
- HTTP Basic disabled.
- JWT filter registered.
- BCrypt password encoder bean.

### `JwtAuthenticationFilter.java`

Runs once per request.

Responsibilities:

- Read bearer token.
- Validate token through `JwtService`.
- Extract user id and role.
- Create Spring Security authentication.
- Store it in `SecurityContextHolder`.

### `ErrorResponse.java`

Standard error response DTO.

Current fields:

- `errorCode`
- `message`
- `requestId`
- `timestamp`

This repo uses camelCase JSON fields for error responses.

### `GlobalExceptionHandler.java`

Global HTTP exception handler.

Currently handles:

- `InvalidCredentialsException`
- `InvalidTokenException`

and maps it to:

```text
HTTP 401
```

### `AuthFlowTest.java`

Test class under:

```text
services/ledger-api/src/test/java/com/fanryan/ledgerflow/auth/AuthFlowTest.java
```

It covers:

- login success
- bad password failure
- refresh success
- bad refresh token failure
- `/auth/me` without a token
- `/auth/me` with a valid access token

### `HealthController.java`

Defines:

```text
GET /health
```

Returns:

```json
{"status":"ok"}
```

### `application.yml`

Configures:

- Spring application name.
- PostgreSQL datasource.
- Flyway location.
- server port `8080`.
- JWT secret and TTLs.

### `V1__create_users_table.sql`

Creates the `users` table.

Important fields:

- `email`
- `password_hash`
- `role`

### `V2__seed_admin_user.sql`

Seeds a local admin user.

The stored password is a BCrypt hash for:

```text
password
```

## 7. Key Spring Concepts

### `@RestController`

Marks a class as an HTTP controller whose methods return response bodies directly as JSON.

Used by:

- `AuthController`
- `HealthController`

### `@Service`

Marks a class as application/business logic.

Used by:

- `AuthService`
- `JwtService`

### `@Configuration`

Marks a class that defines Spring configuration.

Used by:

- `SecurityConfig`

### `@Bean`

Tells Spring to create and manage the returned object.

Used for:

- `SecurityFilterChain`
- `PasswordEncoder`

### `@ConfigurationProperties`

Binds configuration values from `application.yml` into a typed Java object.

Used by:

- `JwtProperties`

### `record`

Java's compact immutable data carrier.

Used for request, response, config, and simple model objects.

### `enum`

A fixed set of named values.

Used by:

- `UserRole`

### `CrudRepository`

Spring Data interface that provides basic persistence methods such as save, find by id, and delete.

`UserRepository` also declares `findByEmail(...)`.

### Constructor Injection

Dependencies are passed through constructors.

Example:

```text
AuthService receives UserRepository, PasswordEncoder, and JwtService.
```

This makes dependencies explicit and testable.

### `OncePerRequestFilter`

Spring web filter base class that runs once per HTTP request.

Used by:

- `JwtAuthenticationFilter`

### `SecurityFilterChain`

Defines the Spring Security request pipeline and authorization rules.

### `SecurityContextHolder`

Stores the current request's authenticated identity.

After JWT validation, it holds an authentication object containing:

- user id
- role authority

### `PasswordEncoder`

Spring Security abstraction for hashing and checking passwords.

Current implementation:

```text
BCryptPasswordEncoder
```

## 8. Security Design Decisions

### Why `/health` Is Public

Health checks should work without a token so local tools, load balancers, and monitoring systems can verify the API is alive.

### Why `/auth/login` Is Public

Users cannot already have an access token before logging in. Login must be reachable without authentication.

### Why `/auth/refresh` Is Public

A refresh request happens when the access token may be expired. The endpoint accepts the refresh token in the request body, validates that token, and then issues a new token pair.

### Why Everything Else Is Authenticated By Default

The system will handle accounts, balances, transactions, reconciliation, and admin operations. The safe default is to protect all endpoints unless explicitly made public.

### Why CSRF Is Disabled

CSRF protection is mainly for browser session applications. LedgerFlow is currently a stateless JSON API using bearer tokens.

### Why Form Login Is Disabled

The API does not use server-rendered login forms or browser sessions.

### Why HTTP Basic Is Disabled

The API should not accept username/password on every request. It uses JWT bearer tokens instead.

### Why Sessions Are Stateless

The server should not store login session state for normal API requests. Clients send tokens, and the server validates them.

### Why Passwords Are Hashed With BCrypt

Raw passwords must never be stored. BCrypt is intentionally slow and salted, making leaked password hashes harder to attack.

### Why JWT Includes `sub`, `role`, `iat`, `exp`, `jti`

- `sub`: identifies the user.
- `role`: supports authorization checks.
- `iat`: records when the token was issued.
- `exp`: limits token lifetime.
- `jti`: gives each token a unique id for auditing or future revocation logic.

### Current Refresh Token Limitation

Refresh tokens are currently stateless JWTs. The API validates their signature and expiry, then checks that the user still exists.

The API does not yet persist refresh tokens, revoke them, rotate them with database tracking, or implement logout. Those are planned hardening steps.

### Why Roles Still Matter With Account Ownership

Account ownership answers:

```text
Is this user allowed to access this specific account?
```

Roles answer:

```text
What type of user is this?
```

Both are useful. For example, an `ADMIN` may inspect operational state while a normal `USER` should only access their own accounts.

## 9. Common Debugging Lessons

### 401 vs 403

`401 Unauthorized` usually means authentication failed or credentials are missing/invalid.

`403 Forbidden` usually means the user is authenticated but not allowed to perform the operation.

### Missing Authorization Header

If a protected endpoint is called without:

```http
Authorization: Bearer <token>
```

`JwtAuthenticationFilter` does not authenticate the request. Spring Security then blocks protected endpoints.

### Invalid Bearer Token

If the token is malformed, expired, tampered with, or signed with the wrong secret, `JwtService` parsing fails. The filter clears the security context and the request continues unauthenticated.

### Wrong BCrypt Hash

If the seeded hash does not match the expected password, login fails even when the email is correct. Earlier versions of the project could produce misleading authorization behavior before the explicit exception handler and stateless security entry point were added.

### Flyway Migration Already Applied

Flyway does not rerun migrations that are already applied. Editing an applied migration requires resetting the local database with:

```bash
docker compose down -v
```

For shared history, create a new migration instead of editing an old one.

### application.yml Config Not Binding

If `JwtProperties` is empty or null, check:

- `ledgerflow.jwt` keys in `application.yml`.
- `@ConfigurationProperties(prefix = "ledgerflow.jwt")`.
- `@EnableConfigurationProperties(JwtProperties.class)` in `LedgerflowApplication`.

### SecurityConfig Not Scanning

Spring scans from package:

```text
com.fanryan.ledgerflow
```

If config classes are outside that package tree, Spring may not discover them.

### Controller Path Mismatch

If curl returns 404, verify the exact annotation path:

- `@PostMapping("/auth/login")`
- `@PostMapping("/auth/refresh")`
- `@GetMapping("/auth/me")`
- `@GetMapping("/health")`

Also make sure the running app has been restarted after code changes.

## 10. Interview Questions and Answers

1. **Why use JWT instead of sessions?**  
   JWTs keep the API stateless: each request carries its own signed credential instead of relying on server-side session storage.

2. **Why use a filter for JWT validation?**  
   Authentication should happen before controllers run. A filter lets every request be checked consistently in the Spring Security pipeline.

3. **What does `PasswordEncoder` do?**  
   It verifies a raw password against a stored BCrypt hash without storing or comparing raw passwords.

4. **How does Spring know which endpoints are public?**  
   `SecurityConfig` declares `.requestMatchers("/health", "/auth/login", "/auth/refresh").permitAll()`.

5. **What is stored in the `users` table?**  
   User id, email, password hash, role, created timestamp, and updated timestamp.

6. **What happens if the access token is expired?**  
   JJWT parsing rejects it. The filter clears the security context, so protected endpoints are not authenticated.

7. **Why does the JWT contain `role`?**  
   The role lets downstream authorization logic decide whether a user is `USER`, `ADMIN`, or `OPERATOR`.

8. **What is `SecurityContextHolder`?**  
   It stores the current request's authentication so controllers and security rules know who the caller is.

9. **What is the principal in the current authentication object?**  
   The principal is the user UUID extracted from the JWT `sub` claim.

10. **Why prefix authorities with `ROLE_`?**  
    Spring Security convention expects role authorities like `ROLE_ADMIN` for role-based checks.

11. **Why is `/auth/login` public?**  
    A user needs to log in before they can have a token, so login cannot require authentication.

12. **Why is `/health` public?**  
    Health checks should work for local tools and monitoring without requiring a JWT.

13. **Why disable CSRF?**  
    This is a stateless JSON API using bearer tokens, not a browser session form app.

14. **Why disable form login?**  
    The API does not use server-side login pages or session cookies.

15. **Why disable HTTP Basic?**  
    The API should not send username/password with every request. It uses JWTs after login.

16. **What does `JwtService.generateAccessToken` include?**  
    It includes subject/user id, role, issued-at time, expiration time, and token id.

17. **What is the difference between access and refresh tokens here?**  
    The access token is shorter-lived for API calls. The refresh token is longer-lived and can be sent to `/auth/refresh` to get a new token pair.

18. **How does `UserRepository.findByEmail` work?**  
    Spring Data JDBC derives a query from the method name and returns an `Optional<User>`.

19. **What happens on bad login?**  
    `AuthService` throws `InvalidCredentialsException`, and `GlobalExceptionHandler` returns `401` with an `ErrorResponse`.

20. **Why are roles not enough for future account APIs?**  
    Roles say what type of user is calling. Account ownership checks will still be needed to decide which specific resources that user can access.

21. **What happens when `/auth/refresh` receives a bad token?**  
    `JwtService` parsing fails, `AuthService` throws `InvalidTokenException`, and `GlobalExceptionHandler` returns `401` with `INVALID_TOKEN`.

22. **Why does refresh load the user from the database after parsing the token?**  
    The token proves it was signed by the server, but the database confirms the user still exists.

23. **Are refresh tokens currently revocable?**  
    Not yet. They are stateless JWTs, so revocation would require future token persistence or deny-listing.

24. **What does `AuthFlowTest` prove?**  
    It proves the main auth behavior works through Spring MVC and Spring Security: login, refresh, invalid errors, and protected `/auth/me`.

## 11. Checklist Before Moving On

Before starting account APIs, be able to explain:

- [ ] Which endpoints are public today.
- [ ] Why every other endpoint is authenticated by default.
- [ ] How `POST /auth/login` flows through controller, service, repository, database, password encoder, and JWT service.
- [ ] What fields exist in the `users` table.
- [ ] Why passwords are stored as BCrypt hashes.
- [ ] What claims are placed in LedgerFlow JWTs.
- [ ] How `JwtAuthenticationFilter` reads and validates bearer tokens.
- [ ] What `SecurityContextHolder` stores after successful JWT validation.
- [ ] Why authorities are stored as `ROLE_ADMIN`, `ROLE_USER`, or `ROLE_OPERATOR`.
- [ ] What `/auth/me` proves about the JWT validation flow.
- [ ] How `/auth/refresh` validates a refresh token and issues a new token pair.
- [ ] What happens when a token is missing or invalid.
- [ ] Why invalid refresh tokens return `INVALID_TOKEN`.
- [ ] How Flyway applies `V1` and `V2` migrations.
- [ ] Why already-applied migrations should not be edited without resetting local DB.
- [ ] What `AuthFlowTest` covers.
- [ ] Why account ownership checks will still be needed later.

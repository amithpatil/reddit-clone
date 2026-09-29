# Reddit Clone: Phase 1 As-Built Report

Sep 29, 2026

This document sits alongside `Reddit Clone: Build & Deployment Plan.md` (the original plan, hereafter "the plan"). The plan is the full 7-phase reference spec — tech stack rationale, complete schema, full API surface, security posture and deployment architecture for the entire project. This document is the as-built ledger for what is actually running in this repository right now: exact versions, exact file contents, exact endpoints, and every place reality diverged from the plan and why. Where Phase 1 was built exactly as the plan specified, this document says so briefly and points back at the plan's section name rather than re-deriving it. Where it differs, it goes into full detail — because most of the divergence was discovered by actually running the system against live Postgres/Redis, not by reading the plan more carefully.

## 1. Status

**Complete:** project skeleton, all 24 tables (Flyway `V1`–`V7`), and four vertical slices wired end-to-end: **auth** (register/login/refresh/me/public-profile), **community** (create/join/leave with atomic subscriber counts), **post** (idempotent submit, keyset-paginated `/new`), **comment** (nested replies on an `ltree` path, depth-10 cap). An ArchUnit test enforces the module-boundary rule from the plan's System architecture section. All four of the plan's Phase 1 checkpoints were verified against a live stack — see §10.

**Not built (Phase 2+, per the plan's Build roadmap):**

| Phase | Scope |
|---|---|
| 2 | Voting/karma/outbox worker, hot/best/top/rising/controversial ranking |
| 3 | Moderation (reports, mod queue, automod, bans), full-text search, Redis feed caching |
| 4 | Notifications, private messages, saved/hidden items, media uploads, account preferences/deletion |
| 5 | Rate limiting, security headers (CSP/HSTS), production Compose stack, CI/CD, monitoring |
| 6 | Load and failure testing |
| 7 | React frontend |

Their tables exist (empty) from the Step 3 migrations — see §5.

## 2. Actual toolchain & versions

Every version below is what's actually in `pom.xml` / `docker-compose.yml` right now, not what was planned before implementation started.

| Component | Plan's version | Actual | Status | Why |
|---|---|---|---|---|
| Java | 21 | **25** | Bumped | Already installed LTS; accepted deviation per user instruction to use latest stable versions throughout. |
| Spring Boot (`spring-boot-starter-parent`) | 3.x | **4.1.1** | Bumped | Current GA (not a milestone) as of Sep 2026; confirmed via `start.spring.io/metadata/client`. Note: the parent `<version>` is `4.1.1`, **not** `4.1.1.RELEASE` — Initializr's own generated pom used the `.RELEASE` suffix and it doesn't exist on Maven Central; see §9.15. |
| `spring-boot-starter-web` | listed by name in the plan's Tech stack | renamed to **`spring-boot-starter-webmvc`** | Renamed | Spring Boot 4 starter reorganization — Initializr's own output for the "Web" dependency selection is this artifact now. See §9.16. |
| PostgreSQL (`postgres` image) | 16 | **18** | Bumped | Latest stable major (18.6 current; 19 still in beta as of Sep 2026). Forced a real fix — see §9.2. |
| Redis (`redis` image) | 7 | **8** | Bumped | Latest stable; Redis relicensed back to open source (AGPLv3/SSPL/RSALv2 tri-license) starting with 8.0, fine for this project's self-hosted internal use. |
| PgBouncer (`edoburu/pgbouncer` image) | untagged, `DATABASE_URL`/`POOL_MODE` only | untagged, **+ `LISTEN_PORT`, `AUTH_TYPE`** | Config added | Kept the same image (its `DATABASE_URL`/`POOL_MODE` env-var interface is well understood; the newer `pgbouncer/pgbouncer` image's file-based config wasn't worth the switch). Two env vars had to be added that the plan's snippet never needed — see §9.4 and §9.5. |
| `spring-boot-starter-flyway` | **not listed at all** | **added explicitly** | Added | Spring Boot 4 split Flyway autoconfiguration into its own starter. See §9.1 — this is the single most consequential gap versus the plan's dependency list. |
| `flyway-core` / `flyway-database-postgresql` | Boot-managed, no version pinned | **13.7.0** via `<flyway.version>` property override | Bumped | Explicit Postgres 18 support confirmed in 13.7.0; Boot 4.1.1's own managed Flyway version predates broad PG18 support. |
| `jjwt-api` / `jjwt-impl` / `jjwt-jackson` | 0.12.6 | **0.13.0** | Bumped | Latest stable; changelog reviewed for breaking changes — none affect the builder API this project uses (`Jwts.builder()...signWith(key)`, `Jwts.parser()...parseSignedClaims()`). |
| `bucket4j-redis` | `com.bucket4j:bucket4j-redis:8.10.1` | **`com.bucket4j:bucket4j_jdk17-lettuce:8.14.0`** | Bumped + coordinates changed | Not just a version bump — see §9.13. Inert in Phase 1 (Phase 5 rate limiting), added now per the plan's own "add it now so nothing breaks mid-build" reasoning. |
| `shedlock-spring` / `shedlock-provider-jdbc-template` | 5.13.0 | **7.10.1** | Bumped | Latest stable, both published together each release. Inert in Phase 1 (Phase 2 outbox worker). |
| `owasp-java-html-sanitizer` | 20240325.1 | **20260313.1** | Bumped | Latest, also carries a fix for CVE-2025-66021. Actively used in Phase 1 (`Sanitizer`, §3). |
| `caffeine` | unspecified | Boot-managed (no explicit `<version>`) | Unchanged | No explicit version needed; inherited from Spring Boot's BOM. |
| `archunit-junit5` | 1.3.0 (test) | **1.5.1** (test) | Bumped | Latest stable. |
| `uuid-creator` | 5.3.7 | **6.1.1** | Bumped | `UuidCreator.getTimeOrderedEpoch()` — the one method this project calls — confirmed unchanged since 5.0.0. |
| `bcprov-jdk18on` (BouncyCastle) | **not listed at all** | **1.86**, runtime scope | Added | `Argon2PasswordEncoder` needs it; `spring-boot-starter-security` doesn't pull it in. Discovered as a `NoClassDefFoundError` at the first password hash — see §9.6. |

**Versions actually running but not directly pinned** (Boot-managed, confirmed from live boot logs): Hibernate ORM **7.4.5.Final**, Spring Security **7.1.1**, Spring Framework **7.0.9**. The plan's code samples were written against Spring Boot 3.x's Hibernate 6.x / Spring Security 6.x — most of that code ported over unchanged (see §6), but the places it didn't are exactly the ones cataloged in §9.

## 3. Project structure

```
src/main/java/com/redditclone/
  RedditCloneApplication.java     — @SpringBootApplication entry point (unchanged from the plan's shape)

  common/
    UuidV7Generator.java          — plan's Identity & auth code, package renamed only
    exception/
      ConflictException.java      — NEW: 409 mapping (plan's AuthService throws it, never defines it)
      UnauthorizedException.java  — NEW: 401 mapping (same gap)
      NotFoundException.java      — NEW: 404 mapping (same gap)
      BadRequestException.java    — NEW: 400 mapping (same gap, incl. comment depth-10 rejection)
      GlobalExceptionHandler.java — NEW: @RestControllerAdvice wiring the four above to HTTP statuses;
                                     without it every one of the plan's thrown exceptions surfaces as a 500
    paging/
      Cursor.java                 — NEW: (createdAt, id) keyset cursor value + FIRST_PAGE sentinel (§9.12)
      CursorCodec.java            — NEW: opaque base64 cursor encode/decode; plan leaves this as a comment
      Listing.java                — NEW: {kind:"Listing", data:{after,before,children}} envelope (plan §API design, prose only)
      Thing.java                  — NEW: {kind, data} wrapper for one listing item
    text/
      Sanitizer.java               — NEW: wraps OWASP PolicyFactory; plan calls sanitize(body) but never defines it

  auth/
    User.java                     — entity; citext columns need columnDefinition (§9.7)
    UserSettings.java             — NEW: plan names it in the Phase 1 checklist but only shows it in Phase 4's Account section
    RefreshToken.java             — entity, matches plan's Identity & auth code
    TokenPair.java                 — NEW: package-private (accessToken, rawRefreshToken) carrier so the
                                     raw refresh token reaches the controller for the cookie but never the JSON body
    JwtService.java                — NEW: the plan's AuthService/JwtAuthFilter call jwt.generateAccessToken/
                                     parseUserId throughout but the class itself is never defined
    JwtAuthFilter.java             — matches plan's Identity & auth code
    SecurityConfig.java            — plan's Argon2 bean + filter chain, relocated from common/ (§9 preamble
                                     below) and extended with the 401 entry point + ERROR-dispatch exemption (§8)
    AuthService.java               — plan's code, adapted: creates default UserSettings row, uses TokenPair
    AuthController.java            — plan's code, adapted: sets the raw refresh token as an HttpOnly cookie
                                     instead of returning it in the JSON body
    UserController.java            — matches plan's Identity & auth code
    UserRepository.java             — NEW: interface (used but never shown in the plan)
    UserSettingsRepository.java     — NEW: interface (UserSettings itself is new, see above)
    dto/
      RegisterRequest.java, LoginRequest.java, AuthResponse.java, UserView.java, PublicProfile.java
                                    — NEW: request/response records; the plan's controllers return entities
                                     or raw prose descriptions, not typed DTOs

  community/
    Community.java                 — entity; citext name needs columnDefinition (§9.7)
    Membership.java                — NEW: plan's CommunityService.join() calls `new Membership(...)` but
                                     the entity itself is never shown
    MembershipId.java              — NEW: composite-PK holder for Membership's (user_id, community_id)
    CommunityModerator.java        — NEW: named in the Phase 1 checklist, not shown until Phase 3 Moderation
    CommunityModeratorId.java      — NEW: composite-PK holder for (community_id, user_id)
    CommunityRepository.java       — plan's code, adapted (findByName, not findByNameIgnoreCase — §9.9)
    MembershipRepository.java      — NEW: interface (Membership itself is new, see above)
    CommunityModeratorRepository.java — NEW: interface (CommunityModerator itself is new, see above)
    CommunityService.java          — plan's code, adapted: also inserts the creator's CommunityModerator row
    CommunityController.java       — matches plan's Communities code
    dto/CreateCommunityRequest.java — NEW: request record

  post/
    Post.java                       — entity; search_vector deliberately unmapped (§6)
    PostRepository.java             — plan's findNewPage kept; adjustScore dropped (Phase 2, no outbox worker yet)
    PostService.java                — plan's code, adapted: adds findById (comment module needs it, §7)
    PostController.java             — plan's code, adapted: listNew implemented against CursorCodec/Listing
    dto/CreatePostRequest.java       — NEW: request record

  comment/
    Comment.java                    — entity; ltree path needs columnDefinition + ColumnTransformer (§9.8)
    CommentRepository.java          — plan's findTopLevel/incrementChildCount kept; adjustScore dropped (Phase 2)
    CommentService.java             — plan's code, adapted: reply() throws BadRequestException at depth 10
    CommentController.java          — re-mapped to resolve a real inconsistency in the plan itself (§7)
    dto/
      ReplyRequest.java              — NEW: request record ({postId, parentId, body} in the body — see §7)
      CommentView.java               — NEW: response record; masks body to "[removed]" per the plan's
                                       soft-delete rule (Data model — Resolved design decisions)
      PostWithCommentsView.java      — NEW: {post, comments} combined response for the comments-thread endpoint
```

Fifty-one `.java` files under `src/main/java`, plus one test (`ModuleBoundaryTest`, §8) and one generated test (`RedditCloneApplicationTests`).

## 4. Local dev stack as actually configured

**`docker-compose.yml`** (repo root):

```yaml
services:
  postgres:
    image: postgres:18
    environment:
      POSTGRES_DB: redditclone
      POSTGRES_USER: app
      POSTGRES_PASSWORD: devpassword
    ports: ["5434:5432"]   # 5432 is taken by a native Postgres process on this machine
    volumes: ["pgdata:/var/lib/postgresql"]   # postgres:18 images store data under the parent dir, not /data (pg_ctlcluster-compatible layout)
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U app -d redditclone"]
      interval: 5s
      timeout: 5s
      retries: 10
  redis:
    image: redis:8
    ports: ["6380:6379"]   # 6379 is taken by another project's Redis container on this machine
  pgbouncer:
    image: edoburu/pgbouncer
    environment:
      DATABASE_URL: postgres://app:devpassword@postgres:5432/redditclone
      POOL_MODE: session
      LISTEN_PORT: 6432   # image defaults to listening on 5432 internally; make it match the published port
      AUTH_TYPE: scram-sha-256   # postgres:18 defaults password_encryption to scram-sha-256; the image's md5 default fails backend auth against it
    ports: ["6432:6432"]
    depends_on:
      postgres:
        condition: service_healthy
volumes:
  pgdata:
```

Every line marked with a comment above is a deviation from the plan's snippet (Tech stack, local dev stack) — none of them are cosmetic:

- **`ports: ["5434:5432"]`, `["6380:6379"]`, and (in `application.yml` below) `server.port: 8081`** — three separate port collisions with other processes/projects already running on this development machine (a native Postgres on 5432, another project's Redis container on 6379, an unrelated Java process on 8080). The plan's ports (5432/6379/8080) are the right defaults for a machine with nothing else running; this machine has other things running. See §9.3.
- **`volumes: ["pgdata:/var/lib/postgresql"]`** — the plan's snippet mounts `/var/lib/postgresql/data`. See §9.2.
- **`LISTEN_PORT: 6432`** and **`AUTH_TYPE: scram-sha-256`** — neither appears in the plan's PgBouncer config at all. See §9.4 and §9.5.

**`src/main/resources/application.yml`**:

```yaml
server:
  port: 8081   # 8080 is occupied by an unrelated process on this machine

spring:
  application:
    name: reddit-clone
  datasource:
    url: jdbc:postgresql://localhost:6432/redditclone   # app traffic -> PgBouncer
    username: app
    password: devpassword
  flyway:
    url: jdbc:postgresql://localhost:5434/redditclone    # migrations -> Postgres directly, bypassing PgBouncer (host port 5434, see docker-compose.yml)
    user: app
    password: devpassword
  jpa:
    hibernate:
      ddl-auto: validate   # Flyway owns the schema, not Hibernate
    open-in-view: false
  data:
    redis:
      host: localhost
      port: 6380

app:
  jwt:
    secret: ${JWT_SECRET:dev-only-change-me-32-bytes-minimum!}
    access-ttl-minutes: 15

management:
  endpoints:
    web:
      exposure:
        include: health
```

Two more deviations from the plan's snippet, both load-bearing:

- **Separate `spring.flyway.url`** pointing at Postgres's direct port (`5434`), distinct from `spring.datasource.url` (`6432`, through PgBouncer). The plan's snippet points both at the same URL. Flyway takes a Postgres advisory lock while migrating; PgBouncer's `session` pool mode (see compose file) makes this survivable, but pointing Flyway straight at Postgres is belt-and-suspenders and works even if `POOL_MODE` is ever changed back to `transaction`.
- **JWT secret padded to 40+ characters.** The plan's placeholder (`dev-only-change-me`, 19 bytes) is too short for HS256, which needs a key ≥ 256 bits (32 bytes); `jjwt` throws `WeakKeyException` on the very first login otherwise. Never actually hit this one at runtime because it was caught during the plan-review pass, but it's a real gap in the plan's snippet worth flagging here.

`open-in-view: false` and the `management.endpoints` block have no equivalent discussion in the plan; the former is a routine Spring Boot hygiene default, the latter just narrows Actuator's exposed surface to `/actuator/health` (used by the boot healthcheck loop during development, not a plan requirement).

## 5. Database schema as migrated

All 7 files run in Flyway's numeric order and are `Successfully validated` / applied on every boot (`flyway_schema_history` confirms `version 7` current). Table-by-table content matches the plan's Detailed database schema DDL (lines 1546–1833) verbatim **except** at the three points below.

**`V1__users.sql`** — opens with three `CREATE EXTENSION IF NOT EXISTS` statements (`citext`, `pg_trgm`, `ltree`) that **the plan's DDL never shows**; the plan's prose says these extensions are "required" but the actual `CREATE EXTENSION` calls are absent from every migration snippet. All three are front-loaded into `V1` even though `pg_trgm`/`ltree` aren't used until `V2`/`V3`, since extensions are database-wide and this avoids an ordering mistake later. Otherwise matches the plan's Identity & auth DDL exactly (`users`, `user_settings`, `refresh_tokens`).

**`V3__content.sql`** — the plan labels its `posts_search_vector_update()` trigger function snippet `V8__search.sql` (line 1620), which is inconsistent with the fixed `V1`–`V7` naming convention the plan states elsewhere (line 1837: "Save each `CREATE TABLE` group above as its own file... `V1__users.sql`, `V2__communities.sql`, `V3__content.sql`, ..."). As actually migrated, the trigger lives in `V3`, positioned after `CREATE TABLE posts` (which it targets) and before `CREATE TABLE comments`. No `V8` file exists.

**`V5__moderation.sql` and `V6__engagement.sql`** — `reports`, `moderation_actions`, and `notifications` are declared `PARTITION BY RANGE (created_at)` in the plan, with `id UUID PRIMARY KEY` as their sole primary key. As actually migrated, all three use **`PRIMARY KEY (id, created_at)`** instead. This is forced by a real Postgres constraint verified directly against the running Postgres 18 instance:

```sql
CREATE TABLE test_partitioned (
  id UUID PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
) PARTITION BY RANGE (created_at);
```
```
ERROR:  unique constraint on partitioned table must include all partitioning columns
DETAIL:  PRIMARY KEY constraint on table "test_partitioned" lacks column "created_at" which is part of the partition key.
```

The plan's literal DDL for these three tables would not have migrated at all. `id` alone is no longer globally unique (only unique per-partition, which for a single unpartitioned parent is the same thing in practice today); application-generated UUIDv7 IDs make a genuine collision astronomically unlikely, so this is a compromise consistent with the plan's own stated tolerance for relaxed integrity elsewhere (Data model — Resolved design decisions).

No child partitions exist for any of the three partitioned tables — valid Postgres (a partitioned table with zero partitions is a legal, queryable, empty table), and nothing writes to them until Phase 3/4, matching the plan's own Build roadmap ordering.

`V2`, `V4`, and `V7` match the plan's Communities, Voting & karma, and Media & infrastructure DDL exactly, including the intentional absence of `REFERENCES` clauses on `post_votes`, `comment_votes`, and `comments.post_id`/`parent_id` (Data model — Resolved design decisions).

**Table count check** (plan's own Phase 1 checkpoint, line 1837): `\dt` lists 24 application tables + `flyway_schema_history` = 25 rows. Confirmed — see §10.

## 6. Class reference per module

Signatures only (not full source — see the file paths in §3 for the actual code).

### auth

| Class | Kind | Real shape |
|---|---|---|
| `User` | `@Entity` | `id` (UUID, no `@GeneratedValue`), `username`/`email` (`String`, `@Column(columnDefinition="citext")` — **no** `@JdbcTypeCode`, see §9.7), `passwordHash`, `status="active"`, `karmaPost`/`karmaComment` (int), `createdAt` |
| `UserSettings` | `@Entity` | `userId` (`@Id`, set to `user.getId()`, not independently generated), `nsfwBlur=true`, `privacyPrefs` (`Map<String,Object>`, `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition="jsonb"`) |
| `RefreshToken` | `@Entity` | `id`, `userId`, `tokenHash` (unique), `familyId`, `expiresAt`, `revokedAt`, `createdAt` |
| `TokenPair` | package-private `record` | `(accessToken, rawRefreshToken)` |
| `UserRepository` | `JpaRepository<User,UUID>` | `existsByEmail`, `findByUsername` — **not** `...IgnoreCase` (§9.9) |
| `UserSettingsRepository` | `JpaRepository<UserSettings,UUID>` | marker only |
| `RefreshTokenRepository` | `JpaRepository<RefreshToken,UUID>` | `findByTokenHash`, `revokeFamily(familyId)`, `revokeAllForUser(userId)` |
| `JwtService` | `@Service` | ctor takes `@Value("${app.jwt.secret}")`/`@Value("${app.jwt.access-ttl-minutes}")`; `generateAccessToken(User)`, `parseUserId(String)` — HS256 via `Jwts.builder()`/`Jwts.parser()` |
| `JwtAuthFilter` | `OncePerRequestFilter` | extracts `Bearer` token, sets `SecurityContextHolder` on success, silently no-ops on `JwtException`/`IllegalArgumentException` |
| `SecurityConfig` | `@Configuration` | `passwordEncoder()` → `Argon2PasswordEncoder(16,32,1,19456,2)`; `filterChain(...)` — full detail in §8 |
| `AuthService` | `@Service` | `register(username,email,rawPassword)`, `login(username,rawPassword)`, `refresh(rawRefreshToken)` — all return `TokenPair`; private `issueTokens`/`sha256` |
| `AuthController` | `@RestController` `/api/v1` | `POST /register`, `POST /access_token`, `POST /access_token/refresh` — all wrap the response via `withRefreshCookie` |
| `UserController` | `@RestController` | `GET /api/v1/me`, `GET /user/{username}/about` |

### community

| Class | Kind | Real shape |
|---|---|---|
| `Community` | `@Entity` | `id`, `name` (`columnDefinition="citext"`), `type="public"`, `description`, `creatorId`, `subscriberCount=0`, `createdAt` |
| `Membership` | `@Entity`, `@IdClass(MembershipId.class)` | `userId`, `communityId` (both `@Id`), `joinedAt` |
| `MembershipId` | `Serializable` | `(userId, communityId)` equals/hashCode |
| `CommunityModerator` | `@Entity`, `@IdClass(CommunityModeratorId.class)` | `communityId`, `userId` (both `@Id`), `permissions` (int; `OWNER_PERMISSIONS = Integer.MAX_VALUE` placeholder), `addedBy`, `addedAt` |
| `CommunityModeratorId` | `Serializable` | `(communityId, userId)` equals/hashCode |
| `CommunityRepository` | `JpaRepository<Community,UUID>` | `findByName` (not `...IgnoreCase`), `incrementSubscriberCount`/`decrementSubscriberCount` (`@Modifying` atomic `UPDATE`) |
| `MembershipRepository` | `JpaRepository<Membership,MembershipId>` | `existsByUserIdAndCommunityId`, `deleteByUserIdAndCommunityId` (returns `long`) |
| `CommunityModeratorRepository` | `JpaRepository<CommunityModerator,CommunityModeratorId>` | marker only |
| `CommunityService` | `@Service` | `create(creatorId,name,description)` — saves `Community`, calls `join()`, inserts owner `CommunityModerator`; `findByName`, `join`, `leave` |
| `CommunityController` | `@RestController` `/r` | `POST /`, `POST /{name}/subscribe`, `DELETE /{name}/subscribe` |

### post

| Class | Kind | Real shape |
|---|---|---|
| `Post` | `@Entity` | all plan fields present **except `search_vector`** (deliberately unmapped — populated by the `V3` trigger, unused until Phase 3 search; `ddl-auto=validate` only checks mapped columns) |
| `PostRepository` | `JpaRepository<Post,UUID>` | `findNewPage(communityId, cursorCreatedAt, cursorId, Pageable)` — the plan's keyset query verbatim; `adjustScore` dropped (no outbox worker exists to call it yet) |
| `PostService` | `@Service` | `create(authorId,communityId,req,idempotencyKey)` — Redis `SETNX`-style idempotency exactly per the plan; `findNewPage(...)`; `findById(postId)` (**new** — needed by `CommentController`, see §7) |
| `PostController` | `@RestController` `/r/{communityName}` | `POST /submit`, `GET /new` — `listNew` implemented against `CursorCodec`/`Listing` (the plan leaves this as a comment) |

### comment

| Class | Kind | Real shape |
|---|---|---|
| `Comment` | `@Entity` | all plan fields; `path` is `@Column(columnDefinition="ltree")` **+** `@ColumnTransformer(write="?::ltree")` — no `@JdbcTypeCode` (§9.8) |
| `CommentRepository` | `JpaRepository<Comment,UUID>` | `findTopLevel(postId, Pageable)` (`parentId IS NULL AND removed=false`, `ORDER BY score DESC`); `incrementChildCount`; `adjustScore` dropped (same reason as `Post`) |
| `CommentService` | `@Service` | `reply(authorId,postId,parentId,body)` — `MAX_DEPTH=10`, throws `BadRequestException` at the limit; `findTopLevel(postId)` (`TOP_LEVEL_PAGE_SIZE=50`); private `toLabel(UUID)` strips hyphens |
| `CommentController` | `@RestController` | `POST /api/comment`, `GET /r/{communityName}/comments/{postId}` — route resolution detailed in §7 |

**Hibernate-mapped columns needing non-obvious handling**, all three verified against a live `INSERT`/`SELECT`, not assumed:

| Column | Postgres type | Annotation used | Why |
|---|---|---|---|
| `users.username`, `users.email`, `communities.name` | `citext` | `@Column(columnDefinition = "citext")` — plain `String`, no `@JdbcTypeCode` | See §9.7 |
| `user_settings.privacy_prefs` | `jsonb` | `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition = "jsonb"` on a `Map<String,Object>` | Hibernate 7's built-in JSON type handling; no custom `UserType` needed, worked on the first attempt |
| `comments.path` | `ltree` | `@Column(columnDefinition = "ltree")` + `@ColumnTransformer(write = "?::ltree")` — plain `String`, no `@JdbcTypeCode` | See §9.8 |

## 7. API endpoints actually wired

| Endpoint | Method | Handler | vs. plan |
|---|---|---|---|
| `/api/v1/register` | POST | `AuthController.register` | matches |
| `/api/v1/access_token` | POST | `AuthController.login` | matches |
| `/api/v1/access_token/refresh` | POST | `AuthController.refresh` | matches |
| `/api/v1/me` | GET | `UserController.me` | matches |
| `/api/v1/me/prefs` | GET/PATCH | — | **not built** (Phase 4, Account) |
| `/api/v1/me` | DELETE | — | **not built** (Phase 4, Account deletion) |
| `/user/{username}/about` | GET | `UserController.about` | matches |
| `/r/{communityName}/{sort}` (hot/top/rising/controversial) | GET | — | **not built** — only `/new` exists (Phase 2 ranking) |
| `/r/{communityName}/new` | GET | `PostController.listNew` | matches (the one sort the plan's Phase 1 checklist actually requires) |
| `/r/{communityName}/comments/{postId}` | GET | `CommentController.getPostWithComments` | matches |
| `/r/{communityName}/about` | GET | — | **not built** |
| `/r` | POST | `CommunityController.create` | matches |
| `/r/{communityName}/subscribe` | POST/DELETE | `CommunityController.subscribe`/`unsubscribe` | matches |
| `/r/{communityName}/submit` | POST | `PostController.submit` | matches |
| `/api/comment` | POST | `CommentController.reply` | **route resolved, not just matched** — see below |
| `/api/vote` | POST/DELETE | — | **not built** (Phase 2) |
| `/api/save`, `/api/hide` | POST | — | **not built** (Phase 4) |
| `/api/report` | POST | — | **not built** (Phase 3) |
| `/api/morechildren` | GET | — | **not built** (Phase 2, ranking-dependent) |
| `/message/inbox` | GET | — | **not built** (Phase 4) |
| `/api/compose` | POST | — | **not built** (Phase 4) |
| `/api/media/upload-url` | POST | — | **not built** (Phase 4) |

**The `/api/comment` route.** The plan's own API design table (line 1863) lists comment creation as `POST /api/comment`. The plan's Detailed class reference — Posts & comments section instead nests comment creation under `@RequestMapping("/r/{communityName}/comments/{postId}")` alongside the top-level-comments *fetch* — two different routes for the same operation, an actual inconsistency inside the plan itself, not two valid readings of one route. As built: `POST /api/comment` takes `{postId, parentId, body}` in the request body (matching the API design table, treated as the documented public contract), and `GET /r/{communityName}/comments/{postId}` is the combined post+comments fetch only. `CommentController.java` carries this exact reasoning as an inline comment at the point of decision.

## 8. Security configuration as implemented

**`permitAll()` list** (`SecurityConfig.filterChain`, in declared order):

1. `dispatcherTypeMatchers(DispatcherType.ERROR)` — **not in the plan at all.** Prevents an unhandled exception on an anonymous request from masking a real 500 as a misleading 401 (§9.11).
2. `/api/v1/register`, `/api/v1/access_token`, `/api/v1/access_token/refresh` — matches plan.
3. `GET /r/*/new`, `GET /r/*/comments/*`, `GET /user/*/about` — matches the plan's Phase 1 subset (the plan's full list also includes `hot|top|rising|controversial`, `/r/*/about`, `/api/morechildren`, none of which exist yet).
4. `/actuator/health` — not in the plan; used by the local dev boot-check loop.
5. `anyRequest().authenticated()` — matches plan.

**`AuthenticationEntryPoint`** — `new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)`, **not in the plan.** Spring Security's own default (anonymous authentication + `.authenticated()`) returns 403 for a missing/invalid token, not 401. The plan's own Phase 1 checkpoint text explicitly states 401 ("without it, or with a garbage token, it returns 401") — this entry point is what makes that literally true. Without it, every unauthenticated-request test in §10 would read 403 where the plan requires 401.

**ArchUnit rules** (`ModuleBoundaryTest`, not present in the plan as code — the plan only describes the intent in prose, System architecture):

| Rule | Prevents |
|---|---|
| `slices().matching("com.redditclone.(*)..").should().beFreeOfCycles()` | A silent circular dependency between modules. Caught one real cycle during development: `common.SecurityConfig` (originally placed in `common/`) depended on `auth.JwtAuthFilter`, while `auth` depended on `common` for `UuidV7Generator`/exceptions — `SecurityConfig` was relocated into `auth/` to break it, since it's fundamentally auth's own Spring Security wiring. |
| `classes().that().resideInAPackage(module).and().haveSimpleNameEndingWith("Repository").should().onlyBeAccessed().byClassesThat().resideInAnyPackage(module, "..common..")` — applied to `auth`, `community`, `post`, `comment` | One module reaching directly into another module's repository instead of going through its service. This is why `CommentController` calls `PostService.findById(...)` rather than injecting `PostRepository` directly. |

## 9. Deviations & fixes from the source plan

Every entry below is a real failure hit by actually running the system, in roughly the order encountered. Each states what the plan assumed, what happened, the root cause, and the fix.

### 9.1 Flyway never ran, silently

**Assumed:** `flyway-core` + `flyway-database-postgresql` on the classpath (Tech stack, `pom.xml` additions) is enough for Spring Boot to auto-run migrations on startup, as it was in Boot 3.x.
**Happened:** app booted cleanly, `\dt` showed zero tables, no Flyway log lines at all — not even a "skipped" message.
**Root cause:** Spring Boot 4 split Flyway's autoconfiguration into its own artifact, `org.springframework.boot:spring-boot-starter-flyway`. Confirmed by checking the local Maven repo for the module (`spring-boot-flyway`, `spring-boot-starter-flyway` both present, dated to the Boot 4 release) and by the complete absence of "Flyway" from a full `-Ddebug` condition-evaluation report.
**Fix:** add `spring-boot-starter-flyway` explicitly (§2 table).

### 9.2 `postgres:18`'s volume layout changed

**Assumed:** `volumes: ["pgdata:/var/lib/postgresql/data"]` (Tech stack, local dev stack) — the layout every `postgres` image has used for years.
**Happened:** container logged an error and exited immediately.
**Root cause:** Postgres 18+ Docker images changed to a `pg_ctlcluster`-compatible layout: a single mount at `/var/lib/postgresql`, with the image placing data in a version-specific subdirectory itself. The old `/data` mount point produces an "unused mount/volume" error and refuses to start.
**Fix:** `volumes: ["pgdata:/var/lib/postgresql"]` (§4).

### 9.3 Three port collisions

**Assumed:** ports 5432 (Postgres), 6379 (Redis), 8080 (app) are free (Tech stack; every plan snippet uses these directly).
**Happened:** `docker compose up` failed with `port is already allocated` (Redis); the app failed with `Web server failed to start. Port 8080 was already in use.`
**Root cause:** this development machine has a native (non-Docker) Postgres already listening on 5432, another project's Docker Redis container on 6379, and an unrelated Java process on 8080 — none of them things to stop or touch.
**Fix:** remapped host ports only (container-internal ports and inter-container hostnames are unaffected): Postgres → `5434`, Redis → `6380`, app → `8081` (`server.port` in `application.yml`). PgBouncer's published port (`6432`) was already free and unchanged.

### 9.4 PgBouncer's actual listen port didn't match the plan's assumption

**Assumed:** the plan's compose snippet publishes PgBouncer on `6432:6432` and never sets a listen-port env var — implying the image listens on `6432` internally by default.
**Happened:** `psql -h localhost -p 6432 ...` failed with `server closed the connection unexpectedly`; container logs showed `listening on 0.0.0.0:5432`.
**Root cause:** the `edoburu/pgbouncer` image's entrypoint script defaults its internal `listen_port` to `5432` (`listen_port = ${LISTEN_PORT:-5432}` in its generated `pgbouncer.ini`), regardless of what host port it's published on.
**Fix:** `LISTEN_PORT: 6432` env var, making the container's internal listen port match its published port (§4).

### 9.5 PgBouncer/Postgres password-encryption mismatch

**Assumed:** implicit in the plan's snippet — PgBouncer authenticates to Postgres with whatever auth method both sides default to.
**Happened:** after fixing §9.4, connections through PgBouncer failed with `FATAL: server login failed: wrong password type`.
**Root cause:** `postgres:18` defaults `password_encryption` to `scram-sha-256` (confirmed via `SHOW password_encryption;`); the `edoburu/pgbouncer` image defaults its own `auth_type` to `md5`. PgBouncer stored an MD5-hashed credential and presented it to Postgres, which expected a SCRAM exchange — auth fails before any query runs.
**Fix:** `AUTH_TYPE: scram-sha-256` env var (§4), which the image's entrypoint script uses both to select the stored-credential format and to set `pgbouncer.ini`'s `auth_type`.

### 9.6 Missing BouncyCastle dependency

**Assumed:** the plan's `SecurityConfig` snippet uses `new Argon2PasswordEncoder(16, 32, 1, 19456, 2)` directly; the plan's dependency list (Tech stack) never mentions BouncyCastle.
**Happened:** first `POST /api/v1/register` call → `NoClassDefFoundError: org/bouncycastle/crypto/params/Argon2Parameters$Builder`.
**Root cause:** Spring Security's `Argon2PasswordEncoder` delegates to BouncyCastle's Argon2 implementation at runtime; `spring-boot-starter-security` does not pull BouncyCastle in as a transitive dependency.
**Fix:** added `org.bouncycastle:bcprov-jdk18on:1.86`, runtime scope (§2).

### 9.7 `citext` binding: `SqlTypes.OTHER` sends `bytea` over the wire

**Assumed:** (from an earlier design-review pass, before this was tested against a live insert) `@JdbcTypeCode(SqlTypes.OTHER)` would satisfy both Hibernate's schema validator and JDBC parameter binding for a `citext` column, the same way it was expected to work for `ltree`.
**Happened, attempt 1:** `ddl-auto=validate` failed at boot: `Schema validation: wrong column type encountered in column [name] in table [communities]; found [citext (Types#OTHER)], but expecting [varchar(255) (Types#VARCHAR)]` — a plain `String` field with no type override reports as `VARCHAR`, which doesn't match citext's catalog type name.
**Happened, attempt 2** (added `@JdbcTypeCode(SqlTypes.OTHER)`): schema validation passed, but the first `existsByEmail`/`findByUsername` query failed: `operator does not exist: citext = bytea`.
**Root cause:** `SqlTypes.OTHER` binds via `PreparedStatement.setObject(value, Types.OTHER)`, which pgjdbc sends over the wire with an unspecified OID — and defaults to binary `bytea` framing when it can't determine the actual type, regardless of INSERT vs. SELECT context.
**Fix:** plain `String` field, no `@JdbcTypeCode` at all, with `@Column(columnDefinition = "citext")`. This alone satisfies `ddl-auto=validate` (Hibernate 7 consults `columnDefinition` as the expected type name during validation, not just the Java type's default JDBC mapping) **and** binds correctly, because a plain `String` field binds via `setString()` (text protocol), and Postgres accepts a text parameter into a `citext` column via an implicit assignment cast. Applied to `User.username`/`email` and `Community.name`.

### 9.8 `ltree` binding needed the citext fix *plus* an explicit cast

**Assumed:** same fix as §9.7 would carry over directly.
**Happened, attempt 1** (`@JdbcTypeCode(SqlTypes.OTHER)`): `column "path" is of type ltree but expression is of type bytea` — identical failure mode to citext's attempt 2, on `INSERT`.
**Happened, attempt 2** (plain `String` + `columnDefinition="ltree"`, no `@JdbcTypeCode` — i.e., exactly the §9.7 fix): schema validation passed, but insert still failed: `column "path" is of type ltree but expression is of type character varying`.
**Root cause:** unlike `citext`, Postgres's `ltree` extension does not define an implicit or assignment cast from `varchar`/`text`. A `setString()`-bound parameter arrives correctly typed as text but Postgres refuses to coerce it into the `ltree` column without an explicit cast.
**Fix:** kept the plain `String` + `columnDefinition="ltree"` mapping, and added `@org.hibernate.annotations.ColumnTransformer(write = "?::ltree")`, which tells Hibernate to wrap the bind parameter placeholder in an explicit `::ltree` cast in the generated SQL. Verified end-to-end: a full depth-10 reply chain inserted and read back correctly (§10).

### 9.9 `IgnoreCase` derived queries break against a `citext`-mapped column

**Assumed:** the plan's `UserRepository`/`CommunityRepository` use `existsByEmailIgnoreCase`, `findByUsernameIgnoreCase`, `findByNameIgnoreCase`.
**Happened:** once §9.7's `@JdbcTypeCode(SqlTypes.OTHER)` was in place (before it was replaced), these queries failed at startup with `BadJpqlGrammarException: ... Parameter 1 of function 'upper()' has type 'STRING', but argument is of type 'java.lang.String' mapped to '1111'` (1111 = `java.sql.Types.OTHER`).
**Root cause:** Spring Data's `IgnoreCase` keyword wraps the comparison in Hibernate's `UPPER(...)` HQL function, which requires a `STRING`-typed argument — incompatible with an `OTHER`-typed field.
**Fix:** dropped `IgnoreCase` from all three method names (`existsByEmail`, `findByUsername`, `findByName`), relying on plain equality. This is not a workaround — `citext` is *already* case-insensitive at the Postgres level, so `UPPER(a) = UPPER(b)` and `a = b` are semantically identical on these columns; the derived-query wrapping was always redundant. Fixed independently of, and prior to discovering, that §9.7's final fix (dropping `@JdbcTypeCode` entirely) would have also sidestepped this specific error — both changes are in the final code.

### 9.10 Spring Security's default is 403, not 401

**Assumed:** the plan's Phase 1 checkpoint states a missing/garbage token returns 401.
**Happened:** `GET /api/v1/me` with no token, and with a garbage token, both returned 403.
**Root cause:** Spring Security's `AnonymousAuthenticationFilter` gives every unauthenticated request an `AnonymousAuthenticationToken` (technically "authenticated", just with `ROLE_ANONYMOUS`). `.anyRequest().authenticated()` then denies this principal via `AccessDeniedException` → 403, not `AuthenticationException` → 401. This is standard Spring Security behavior going back years, unrelated to the Boot 4/Security 7 version bump.
**Fix:** explicit `AuthenticationEntryPoint` (§8) — `ExceptionTranslationFilter` specifically checks whether the current principal is anonymous and, if so, routes to the entry point instead of the access-denied handler, so this fix reliably converts the anonymous-denied case to 401 without affecting a genuinely authenticated-but-forbidden case (none exist yet in Phase 1).

### 9.11 Error-dispatch masked a real 500 as a misleading 401

**Assumed:** not applicable — this bug was self-inflicted by the §9.10 fix, then found and fixed in the same session.
**Happened:** `GET /r/programming/new` returned 401 with the security-filter's characteristic headers (`X-Frame-Options`, `Cache-Control: no-cache`, etc.) — even with a valid, freshly issued token. TRACE-level Spring Security logging showed the request actually *passing* authorization (`Secured GET /r/programming/new`) before the 401 appeared, which ruled out a matcher problem.
**Root cause:** the real failure was unrelated (§9.12, an `ArithmeticException` deep in query parameter binding). When that exception propagated unhandled out of the `DispatcherServlet`, the servlet container performed an internal forward to Boot's `/error` endpoint — a **second**, unauthenticated request that re-entered the same security filter chain. `.anyRequest().authenticated()` correctly denied *that* dispatch, producing a 401 that had nothing to do with authentication and completely hid the real 500.
**Fix:** `.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()` (§8), so the error-rendering dispatch is never itself subject to authorization — only the original request is. Once added, the same underlying bug (§9.12) surfaced as its real status: 500, with the actual exception visible in the response body and logs.

### 9.12 `Instant.MAX` overflows a `long` during Postgres binding

**Assumed:** (again, self-inflicted during implementation, not from the plan) `Cursor.FIRST_PAGE` used `Instant.MAX` as a "match every row" sentinel for the first page of keyset pagination.
**Happened:** every call to `GET /r/{name}/new` with no `after` parameter threw `InvalidDataAccessApiUsageException: java.lang.ArithmeticException: long overflow` — masked as a 401 until §9.11 was fixed, then visible directly.
**Root cause:** `Instant.MAX`'s epoch-second value (`31556889864403199`), converted to epoch milliseconds internally by Hibernate/pgjdbc for `TIMESTAMPTZ` parameter binding, exceeds `Long.MAX_VALUE` when multiplied by 1000.
**Fix:** replaced the sentinel with `Instant.parse("9999-12-31T23:59:59Z")` — still centuries past any real `created_at` value, well within both Postgres's `timestamptz` range and a safe `long` of epoch millis.

### 9.13 `bucket4j-redis` doesn't exist as a real jar anymore

**Assumed:** the plan's `pom.xml` addition is `com.bucket4j:bucket4j-redis:8.10.1`.
**Happened:** while researching latest versions (not at build time), found the project restructured into JDK-specific artifacts. The naive latest-version guess, `com.bucket4j:bucket4j_jdk17-redis:8.14.0`, resolves and appears in search results but **fails at build** — `Could not find artifact` — because it only ever published as a `.pom` (a dependency aggregator with no attached jar).
**Root cause:** the Bucket4j project splits its Redis integration by JDK baseline (`_jdk8`, `_jdk11`, `_jdk17`) and, within each, by client library (`-lettuce`, `-jedis`, `-redis-common`); `..._jdk17-redis` is a pom-packaging umbrella, not something with actual classes.
**Fix:** `com.bucket4j:bucket4j_jdk17-lettuce:8.14.0` — the artifact that actually contains `RedisClient`-based `ProxyManager` classes and matches Spring Data Redis's default client (Lettuce). Inert in Phase 1; verified only by `dependency:tree` resolving cleanly, not by exercising the rate limiter (that's Phase 5).

### 9.14 `UsernamePasswordAuthenticationFilter` import — not a framework change

**Assumed:** N/A — this was a transcription mistake made while writing `SecurityConfig`, not something the plan or Spring Boot 4 caused.
**Happened:** `cannot find symbol: class UsernamePasswordAuthenticationFilter` compiling against `import org.springframework.security.authentication.UsernamePasswordAuthenticationFilter;`.
**Root cause:** wrong package — the class has always lived at `org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter` (confirmed unchanged in Spring Security 7.1.1 by inspecting the actual jar's contents). Worth recording precisely because the name alone plausibly suggests the `.authentication` package rather than `.web.authentication`, and it's an easy mistake to repeat.
**Fix:** corrected import; no version or behavior change involved.

### 9.15 `spring-boot-starter-parent` version suffix

**Assumed:** N/A.
**Happened:** `start.spring.io`'s own metadata endpoint (`/metadata/client`) labels the current release `4.1.1.RELEASE`, and Initializr's generated `pom.xml` used that exact string as the parent `<version>`. `./mvnw compile` failed: `spring-boot-starter-parent:pom:4.1.1.RELEASE ... was not found`.
**Root cause:** Initializr's internal version *identifiers* keep a legacy `.RELEASE` suffix for display/selection purposes, but the actual Maven Central artifact coordinate for Spring Boot 3.x/4.x releases has no such suffix.
**Fix:** `<version>4.1.1</version>` (no suffix) — confirmed by that single change resolving the parent POM successfully.

### 9.16 `spring-boot-starter-web` → `spring-boot-starter-webmvc`

**Assumed:** the plan's Tech stack table doesn't enumerate Initializr's base dependencies by artifact ID, but "Web" has always meant `spring-boot-starter-web` since Spring Boot's earliest versions.
**Happened:** the project generated via Initializr's REST API with the `web` dependency selection produced a `pom.xml` containing `spring-boot-starter-webmvc`, not `spring-boot-starter-web`.
**Root cause:** Spring Boot 4's starter reorganization; `web` now resolves to the `webmvc`-specific starter (Boot 4 also has a WebFlux-equivalent path, making the old generic `-web` name ambiguous). Purely a naming change — no code in this project referenced the old artifact ID directly, so nothing else was affected.
**Fix:** none needed; noted here because it's a real, silent divergence from what "the Web dependency" has meant in every prior Spring Boot major version, and would matter to anyone hand-editing `pom.xml` against the plan's assumptions.

## 10. Verification evidence

Real results from the running system, each mapped to its row in the plan's Definition of done table.

### Phase 1 — Authentication (Tier 1)

```
POST /api/v1/register {"username":"alice","email":"alice@example.com","password":"Sup3rSecret!"}
→ HTTP 200
  Set-Cookie: refresh_token=6ca2e6ad-...; Path=/api/v1; Max-Age=2592000; HttpOnly; SameSite=Strict
  {"accessToken":"eyJhbGciOiJIUzI1NiJ9...","tokenType":"Bearer"}

GET /api/v1/me  (Authorization: Bearer <token>)
→ HTTP 200  {"id":"01a0ee43-...","username":"alice","email":"alice@example.com","karmaPost":0,"karmaComment":0,"status":"active","createdAt":"2026-09-29T17:43:05.461790Z"}

GET /api/v1/me  (no header)            → HTTP 401
GET /api/v1/me  (Authorization: garbage) → HTTP 401
GET /user/alice/about  (no header)     → HTTP 200  {"username":"alice","karmaPost":0,"karmaComment":0,"createdAt":"..."}
POST /api/v1/access_token/refresh (refresh cookie) → HTTP 200, new rotated cookie + new access token
```

Satisfies: **Phase 1 — Authentication** ("Register, login, refresh, `/me` and public profile all work end-to-end with real Argon2id + JWT").

### Phase 1 — Communities (Tier 1)

Created community `programming`, then fired 20 concurrent `POST /r/programming/subscribe` requests from 20 distinct users (plus the creator, auto-joined at creation):

```sql
SELECT subscriber_count FROM communities WHERE name='programming';        -- 21
SELECT count(*) FROM memberships WHERE community_id = '01a0ee44-...';     -- 21
```

Both numbers exact, matching, under real concurrent load — no lost updates from the atomic `UPDATE ... SET subscriber_count = subscriber_count + 1` pattern.

Satisfies: **Phase 1 — Communities** ("Create, join and leave a community; subscriber counts stay accurate").

### Phase 1 — Posts (Tier 1)

```
POST /r/programming/submit  (Idempotency-Key: abc-123)  → id 01a0ee44-98cf-...
POST /r/programming/submit  (Idempotency-Key: abc-123, identical body)  → id 01a0ee44-98cf-...  (same id)
```

Seeded 60 more posts, then paged `/r/programming/new` with no `OFFSET` anywhere in the query (`PostRepository.findNewPage`, §6):

```
page 1: 25 children, after=MTc5MDcwMzg3NjA3NzowMWEwZWU0NC1iN2VkLTcwODctYmNhMS02MDQ2MWU2NTJlMDY
page 2 (using page 1's after): 25 children, zero id overlap with page 1
```

Satisfies: **Phase 1 — Posts** ("Submit a post (idempotently) and page through `/new` with keyset pagination (no `OFFSET`)").

### Phase 1 — Comments (Tier 1)

Built a real 11-comment reply chain on one post:

```sql
SELECT depth, path FROM comments WHERE post_id='01a0ee44-98cf-...' ORDER BY depth;

 depth |                                    path
-------+-----------------------------------------------------------------------------
     0 | 01a0ee49885776d9b283e8b8bd0f1660
     1 | 01a0ee49885776d9b283e8b8bd0f1660.01a0ee49ac4a735c95f8c8c491e25c1b
     2 | 01a0ee49885776d9b283e8b8bd0f1660.01a0ee49ac4a735c95f8c8c491e25c1b.01a0ee49acb17feb...
     ...
    10 | 01a0ee49885776d9b283e8b8bd0f1660. ... .01a0ee49b0307da5b9fcd3df7f87a3e9   (11 segments)
```

11th nested reply (depth 11):

```
POST /api/comment {"postId":"...","parentId":"<depth-10-comment-id>","body":"too deep"}
→ HTTP 400  {"status":400,"error":"Bad Request","message":"max comment depth reached"}
```

`GET /r/programming/comments/{postId}` returned exactly 1 comment (the single top-level one; the other 10 are nested replies correctly excluded by `parentId IS NULL`), confirming the top-level fetch is filtered and page-capped by construction (`Pageable.ofSize(50)`, never unbounded), not merely by there being few rows.

Satisfies: **Phase 1 — Comments** ("Reply at depth 10 works; depth 11 is rejected; `[removed]` tombstones don't break threads" — tombstone masking itself, `CommentView.from`, is implemented and code-reviewed but not exercised by a live "remove a comment" call, since removal is a Phase 3 Moderation action).

### Also satisfies

- **Phase 1 — Project skeleton**: `docker compose up` starts Postgres/Redis/PgBouncer; app boots against them cleanly.
- **Detailed database schema**: all 24 tables migrate cleanly via Flyway (`\dt` → 24 app tables + `flyway_schema_history`); every migration marked `success = t` in `flyway_schema_history`.
- **System architecture — How the pieces fit together**: the module-boundary rule is not just followed but mechanically enforced — `ModuleBoundaryTest`'s 5 ArchUnit rules pass on every build (§8).

## 11. How to run it

```bash
cd "/Users/amithpatil8/Amith Projects/Java/reddit-clone"
docker compose up -d      # Postgres 18 (5434), Redis 8 (6380), PgBouncer (6432)
./mvnw spring-boot:run    # app on localhost:8081 — 8080 is occupied by an unrelated process on this machine
```

Health check: `curl http://localhost:8081/actuator/health` → `{"status":"UP"}`.

Direct Postgres access (bypassing PgBouncer, e.g. for `psql`): `localhost:5434`, user `app`, db `redditclone`, password `devpassword`. Redis: `localhost:6380`, no auth. None of these ports are the defaults the plan assumes (§9.3) — this is specific to the current development machine's already-running processes, not a property of the application itself; a clean machine could use the plan's original 5432/6379/8080 by simply reverting the port lines in `docker-compose.yml` and `application.yml`.

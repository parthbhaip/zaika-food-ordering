# NOTES - Zaika build log

Append-only memory between build iterations. Read the end of this file before starting work.

## Summary so far
Nothing compressed yet. When this file passes ~300 lines, fold the oldest entries into this section.

---

## Setup (2026-10-04)
- JDK: Eclipse Temurin 21.0.12.1 at `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot` (JAVA_HOME and PATH set machine-wide). Shells started before the install do not see it; set `JAVA_HOME` and `PATH` explicitly there. An old Oracle Java 8 stub (`...\Oracle\Java\java8path`) is also on PATH, after Temurin; `java -version` must report 21.
- The project path contains a space (`D:\New folder`, Git Bash `/d/New folder`). Always quote paths.
- The build is driven by Claude Code workflows inside one session (a builder agent plus an independent reviewer per task) rather than `loop.ps1`. Installed Claude Code is 2.1.234, below loop.ps1's 2.1.259 minimum; run `claude update` before using loop.ps1.

## T01 Generate the Spring Boot project (2026-10-04)
- Generated via start.spring.io (bootVersion 4.1.1 = latest stable 4.x; 4.2.0-M2 and snapshots skipped), copied with `cp -rn`; starter `.gitignore` merged into ours, starter `HELP.md` deleted (also ignored). `_starter/` stays git-ignored.
- Versions: Spring Boot 4.1.1, Spring Framework 7.0.9, Spring Security 7.1.1, Hibernate ORM 7.4.5.Final, Thymeleaf 3.1.5, thymeleaf-extras-springsecurity6 3.1.5 (Boot-managed; no `springsecurity7` artifact exists, the 6 module is the one for Security 6/7), Flyway 12.4.0, H2 2.4.240, MySQL Connector/J 9.7.0, Tomcat 11.0.24, Maven 3.9.16 (wrapper 3.3.4).
- htmx WebJar `org.webjars.npm:htmx.org` pinned to **2.0.11** via `${htmx.version}` in pom. 4.0.0 exists but is a breaking rewrite; CLAUDE.md's htmx config (`includeIndicatorStyles`, no `hx-on`) targets the 2.x API. `webjars-locator-lite` 1.1.4 (Boot-managed) makes `/webjars/htmx.org/dist/htmx.min.js` work without a version in the path.
- Boot 4 starters are modular: web is `spring-boot-starter-webmvc`, H2 console is `spring-boot-h2console`, and each starter has a `-test` sibling. MockMvc annotation lives at `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.
- Tests: `ZaikaApplicationTests` (context loads), `StarterDependenciesTest` (htmx served from WebJar, `SpringSecurityDialect` bean present). Security is still Boot's default (everything authenticated) until T07.

## T02 Configuration and profiles (2026-10-04)
- `application.properties` holds shared settings + `app.*` defaults; profile files `-dev` (H2 file `./data/zaika`, MySQL mode, H2 console at `/h2-console`, seed on), `-test` (in-memory H2 `zaika`, `DB_CLOSE_DELAY=-1`, `spring.test.database.replace=none` so `@DataJpaTest` keeps MySQL mode + Flyway), `-mysql` (all from env vars; also imports an optional `.env` file via `spring.config.import=optional:file:.env[.properties]`; real env vars win).
- `com.zaika.config.AppProperties` is a validated record (`@ConfigurationPropertiesScan` on the app class): brandName, zone, uploadDir (String), seedDemo, platformFee 5.00, gstRate 0.05 (a fraction, not a percent), freeDeliveryThreshold 499.00, riderBasePayout 30.00 (chosen value). Money is normalized to scale 2; more than 2 decimals fails startup.
- Mail: `com.zaika.notification.LoggingMailSender` (a `JavaMailSender`) is active when `app.mail.log-only=true` (dev + test); it logs recipient and subject only, never the body. mysql gets Boot's SMTP `JavaMailSenderImpl` from SMTP_* env vars (SMTP_HOST defaults to localhost). T10's MailService should send through the `JavaMailSender` bean; if it needs the reset link in dev logs, log it there.
- Tests: every `@SpringBootTest` uses `@ActiveProfiles("test")`; surefire also sets `spring.profiles.active=test` as a backstop (an explicit `@ActiveProfiles("dev")` still wins, see `DevProfileTest`). `MysqlProfileTest` boots the mysql profile on H2 by passing DB_URL etc. as test properties, and checks every `${VAR}` in `application-mysql.properties` is documented in `.env.example`; add new env vars to both.
- Boot 4 renamed `server.error.*` to `spring.web.error.*` (the old keys are silently ignored): use `spring.web.error.whitelabel.enabled=false` / `spring.web.error.include-stacktrace=never`. `ZaikaApplicationTests` proves the whitelabel `error` View bean is absent; once T03 adds `templates/error/*.html` that bean stays absent anyway.
- `spring.h2.console.enabled=false` in the shared file because devtools' `spring-devtools.properties` turns the console on in any profile (e.g. `spring-boot:run` with mysql); only `-dev` sets it true. `MysqlProfileTest` pins every env var as a test property so a shell with `SEED_DEMO`/`SMTP_*` set or a local `.env` cannot change results; defaults are checked by resolving the file's `${VAR:default}` values.
- `.gitattributes` now starts with `* text=auto` (index stays LF; Windows checkouts get CRLF via autocrlf).
- ADMIN_EMAIL / ADMIN_PASSWORD are not in `.env.example` yet; T11 adds them when it reads them. H2 console is enabled in dev but security still blocks it until T07 permits `/h2-console/**` (dev only, CSP relaxed there only).

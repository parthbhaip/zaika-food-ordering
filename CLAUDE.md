# Zaika - Online Food Ordering System

Multi-restaurant food ordering marketplace (customers, restaurants, delivery riders, admin), built entirely in Java.
This file is the rulebook. It is loaded in every session. The build loop itself is driven by PROMPT.md (what to do each iteration) and PLAN.md (the task list).

## Project settings (edit BEFORE the first run - renaming after T01 is painful)
- Brand name: **Zaika** - tagline: "Your city's best kitchens, delivered."
- Base package `com.zaika` - Maven groupId `com.zaika`, artifactId `zaika`
- Demo city for seed data: **Kolhapur, Maharashtra** (fictional restaurant names, real neighbourhood names)
- Currency INR, displayed with Indian digit grouping: `₹1,23,456.50` (`en-IN` locale)
- Time zone: Asia/Kolkata (single-country app, no DST)
- Demo accounts (dev seed only), password for all `Demo@1234`:
  `admin@zaika.test`, `owner1@zaika.test`, `owner2@zaika.test`, `rider1@zaika.test`, `rider2@zaika.test`, `customer@zaika.test`

## Roles
| Role | Can do |
|---|---|
| CUSTOMER | browse, search, cart, coupons, checkout, pay (COD / mock online), track live, cancel early, review, favourites |
| RESTAURANT_OWNER | one restaurant: profile, menu, accept/reject/prepare orders, reports |
| DELIVERY_PARTNER | go online, claim orders, pick up, deliver with customer OTP, earnings |
| ADMIN | approve/suspend restaurants and riders, users, coupons, cuisines, all orders, platform KPIs |

## Stack (fixed - do not swap anything)
- Java 21 language level. Maven through the wrapper (`mvnw`). No Gradle.
- Spring Boot 4.x, latest stable, generated from start.spring.io in T01. Record exact versions in NOTES.md.
- Spring MVC + Thymeleaf (server-rendered HTML) + Thymeleaf Spring Security extras.
- htmx from a WebJars Maven dependency for partial updates. No hand-written fetch/AJAX code.
- Spring Security (form login for the website; JWT resource server for `/api/**` in Phase 8).
- Spring Data JPA (Hibernate) + Flyway.
- Databases: H2 file DB in `dev`, H2 in-memory in `test`, MySQL 8 in `mysql` profile.
- Bean Validation, Spring Mail (logging sender in dev/test, SMTP in mysql).
- Tests: JUnit 5, Spring Boot Test, MockMvc, spring-security-test, AssertJ.
- springdoc-openapi (version compatible with the generated Boot version) in Phase 8.

Not allowed: Node/npm or any frontend build step, React/Vue/Angular, Tailwind, Bootstrap or any CSS framework, jQuery, CDN-hosted JavaScript, Lombok, Kotlin, Gradle.
Small vanilla JS files in `static/js/` are allowed only where HTML + htmx cannot do the job: under ~60 lines each, no inline scripts, no inline `on*=` handlers.

## Architecture
Package by feature under `com.zaika`:
`common` (BaseEntity, errors, formatting helpers), `config`, `security`, `user` (accounts, addresses, password reset), `catalog` (cuisines, restaurants, menu, search), `cart`, `coupon`, `order` (orders, workflow, tracking), `payment`, `delivery` (riders, claims, earnings), `review`, `notification`, `owner` (owner panel controllers), `partner` (rider panel controllers), `admin`, `api` (REST controllers + DTOs), `storage` (uploads, placeholders), `web` (public pages).

Layering: Controller -> Service -> Repository.
- Controllers are thin. Bind to form/DTO classes (Java records or plain classes with Bean Validation), never to entities.
- Services own all business rules, `@Transactional` boundaries, ownership checks and state changes.
- Repositories are Spring Data interfaces. List pages use fetch joins or entity graphs - no N+1 queries.
- Constructor injection only. No field injection, no static mutable state.
- Money: `BigDecimal`, scale 2, `RoundingMode.HALF_UP`. Never `double`/`float` for money.
- Time: `LocalDateTime` in Asia/Kolkata, always obtained from an injected `java.time.Clock` bean so tests can fix the time.
- Order status changes go ONLY through `OrderWorkflow` (the one place that knows allowed transitions, who may make them, and which timestamp to set).
- IDs: Long auto-increment internally. Customer-facing order code like `ZK-26-000123`. Restaurants are addressed by slug.
- Web and API share the same services. Business logic never lives in controllers, and is never duplicated between web and API.

## Database rules
- Flyway is the only schema source. `spring.jpa.hibernate.ddl-auto=validate`, `spring.jpa.open-in-view=false`.
- Migrations: `src/main/resources/db/migration/V{n}__{snake_case}.sql`. NEVER edit a committed migration. Add a new one.
- SQL must run on both H2 (`MODE=MySQL;DATABASE_TO_LOWER=TRUE`) and MySQL 8: `BIGINT AUTO_INCREMENT` keys, explicit `VARCHAR` lengths, `DECIMAL(10,2)` money, `BOOLEAN`, `DATETIME(6)` timestamps, named foreign keys and indexes, no vendor-only functions.
- Every foreign key and every column used in WHERE/ORDER BY on list pages gets an index.

## UI and design system (the site must look like a real product)
Quality bar: a polished, modern food-delivery product a recruiter would believe is live. Not a template look, not a student-project look.
- No lorem ipsum, no "coming soon", no dead links or buttons, no unstyled browser controls.
- Copy is short, friendly and specific: "Delivered in 32 min", "Pure veg", "₹300 for two", "Add ₹80 more for free delivery".
- Everything is original. Never copy another company's logo, name, colours, illustrations or layout signatures.

Tokens live in `static/css/tokens.css` as CSS variables. Everything else uses the variables, never raw hex values.
- Primary (buttons, links): `#D1440F`, hover `#B23A0C`, tint `#FFF1EB`
- Accent (stars, highlights): `#F2A516`
- Ink `#1C1C22`, secondary text `#4A4A55`, muted text `#6B6B76`, lines `#ECE8E3`
- Page background `#FAF8F5`, surfaces `#FFFFFF`
- Veg / success `#17803A`, non-veg / danger `#B3261E`, warning `#B26A00`
- Radius 8 / 12 / 20 px, pill 999px. Shadows: soft 1-2 layer, never harsh. Spacing on a 4px scale.
- Font: Plus Jakarta Sans, self-hosted woff2 in `static/fonts/` (works offline at a college demo), `system-ui` fallback. Headings 700-800, body 400-500.

Components (in `static/css/app.css`): buttons (primary, secondary, ghost, danger; sm/md/lg; disabled + loading), inputs/select/textarea with label + help + error, checkbox, radio, switch, cards, restaurant card, menu item row, badges/chips, rating pill, veg/non-veg mark, tabs, order-status stepper, tables, pagination, toasts/flash, modal (native `<dialog>`), empty states, skeleton loaders.
- Layout: mobile-first. Breakpoints 640 / 960 / 1200px. Content max-width 1200px. 16px side gutters on phones. Sticky header. On phones, a bottom "View cart" bar when the cart has items.
- Veg mark: green square outline with a green dot. Non-veg: red-brown square outline with a triangle. Always paired with screen-reader text.
- Images: fixed aspect ratios (restaurant cards 16:10, menu items 1:1), `object-fit: cover`, `loading="lazy"`, width/height attributes, generated SVG placeholder fallback.
- Accessibility: WCAG AA contrast, visible focus rings, a label on every input, alt text, `aria-live` for toasts and the cart count, keyboard-usable dialogs, skip-to-content link, `lang="en"`.
- Icons: one sprite `static/img/icons.svg` of simple original line icons (24px grid, 1.75 stroke, `currentColor`).
- Templates: shared layout and fragments in `templates/layouts` and `templates/fragments`. Every page uses the base layout. Page titles: `Page name | Zaika`.
- Forms: server-side validation with inline messages, keep user input on error, Post/Redirect/Get with a flash message on success.
- htmx is progressive enhancement only: every action must still work as a normal form POST with JavaScript off. For htmx requests (header `HX-Request`) return a Thymeleaf fragment. Keep counts in sync with `hx-swap-oob`.

## Security rules
- BCrypt passwords. Never log passwords, tokens, OTPs or full phone numbers.
- CSRF on for all web forms (`th:action` adds the token). htmx sends the token from meta tags via an external JS config file.
- Content-Security-Policy: `default-src 'self'; script-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'` (dev may relax it for `/h2-console/**` only). Therefore: no inline `<script>`, set `htmx.config.includeIndicatorStyles = false`, do not use `hx-on`.
- Authorization in two layers: URL rules in the security config AND ownership checks in services (owner -> own restaurant, rider -> own deliveries, customer -> own orders/addresses/cart). Every such endpoint gets a test proving another user gets 403 or 404.
- Never `th:utext` with user-supplied content.
- Uploads: allowlist jpg/png/webp checked by magic bytes, max 2 MB, random file names, stored outside the classpath under `./uploads`.
- Secrets only from environment variables. `.env` is git-ignored. `.env.example` is committed.
- H2 console only in the `dev` profile.
- The mock payment page must be clearly labelled "TEST MODE - no real money" and must not imitate any real payment provider.

## Commands
- Full build + all tests: `./mvnw -q verify` (Bash) or `.\mvnw.cmd -q verify` (PowerShell)
- One test class: `./mvnw -q -Dtest=ClassName test`
- Do NOT start the server (`spring-boot:run`) inside the loop. It never exits and blocks the session. Prove pages work with MockMvc tests.
- If the wrapper script is not executable in Bash: `chmod +x mvnw`.

## Testing rules
- Every task ships with tests for its acceptance criteria.
- Unit tests for pure rules (pricing, coupons, order workflow, OTP, open-hours logic).
- `@DataJpaTest` for custom queries. `@SpringBootTest` + MockMvc for pages and APIs: assert status, view/fragment, key content, and security (anonymous, wrong role, wrong owner).
- Tests run on the `test` profile (H2 in-memory) with a fixed `Clock`.
- The build must be green before every commit. Never delete or weaken a test just to go green.

## Git
- One task = one commit: `Txx: <task title>`. Never push, never force, never rewrite history.
- Never run `git reset --hard`, `git clean`, `git checkout -- .` or delete stashes. To set work aside: `git stash push -u -m "<reason>"`.

## NOTES.md (shared memory between iterations)
- Append-only log: what each task built, key files, decisions, gotchas. Read the end of it before starting work.
- When it passes ~300 lines, compress the oldest entries into the "Summary so far" section at the top.
- Nobody is watching the loop. Never ask questions; make the reasonable choice and record it in NOTES.md.

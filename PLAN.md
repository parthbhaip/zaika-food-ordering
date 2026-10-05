# PLAN - Zaika build tasks

How this file works
- The loop always takes the FIRST `- [ ]` task from the top. The order is the dependency order. Do not skip ahead.
- Status: `- [ ]` open, `- [x]` done, `- [!]` blocked (reason after `-- BLOCKED:`).
- The indented bullets under a task are its acceptance criteria. All of them must be true before the task is checked.
- New tasks: next free ID, placed in the right phase. If a task is too big for one iteration, finish a coherent part, commit it, and add the rest as `Txxb` directly below. Never delete tasks.

---

## Phase 0 - Foundation

- [x] T01 Generate the Spring Boot project
  - Use the start.spring.io HTTP API (curl) to generate a Maven project into `_starter/`: latest stable Spring Boot 4.x, Java 21, groupId `com.zaika`, artifactId `zaika`, name `Zaika`, package `com.zaika`, jar packaging. Dependencies: web, thymeleaf, security, data-jpa, validation, flyway, h2, mysql, mail, devtools, actuator. If an ID is rejected, check `https://start.spring.io/dependencies` for the current IDs.
  - Copy into the repo root WITHOUT overwriting existing files (`cp -rn _starter/. .`), then merge the starter's `.gitignore` entries into ours. `_starter/` stays git-ignored.
  - Add: htmx WebJar, `webjars-locator-lite`, Thymeleaf Spring Security extras matching the generated Spring Security version.
  - `./mvnw -q verify` passes. Record Spring Boot, Spring Security, Hibernate, Thymeleaf and htmx versions in NOTES.md.

- [x] T02 Configuration and profiles
  - `application.properties`: app name, `spring.profiles.default=dev`, `ddl-auto=validate`, `open-in-view=false`, response compression, whitelabel error page off.
  - `application-dev.properties` (H2 file DB `./data/zaika`, MODE=MySQL, H2 console, logging mail sender, `app.seed-demo=true`), `application-test.properties` (H2 in-memory), `application-mysql.properties` (DB_URL / DB_USER / DB_PASSWORD and SMTP settings from env vars, `app.seed-demo` from env var `SEED_DEMO`, default false).
  - Typed `@ConfigurationProperties` `AppProperties` with validation: brand name, zone, upload dir, seed-demo flag, platform fee (₹5), GST rate (5%), free-delivery threshold (₹499), rider base payout.
  - `.env.example` lists every env var with a comment. A context-loads test passes on the `test` profile.

- [x] T03 Core building blocks
  - `BaseEntity` (id, createdAt, updatedAt via JPA auditing, `@Version`), `Clock` bean (Asia/Kolkata), `NotFoundException`, `ForbiddenException`, `@ControllerAdvice` that maps them to error pages.
  - Formatting helper bean usable in Thymeleaf (e.g. `${@fmt.money(x)}`, `${@fmt.time(t)}`, `${@fmt.relative(t)}`): ₹ with en-IN grouping, IST times, "5 min ago".
  - Branded error templates for 403, 404 and 500 with helpful copy and a link home.
  - Unit tests for the formatters (₹1,23,456.50, ₹0.00, rounding).

- [x] T04 Design system
  - `static/css/tokens.css` and `static/css/app.css` implementing every component listed in CLAUDE.md > UI, including hover/focus/disabled states.
  - Self-hosted Plus Jakarta Sans woff2 (400, 500, 600, 700, 800; latin) downloaded from Fontsource into `static/fonts/`, `font-display: swap`.
  - `static/img/icons.svg` sprite with original line icons: search, cart, user, location, clock, star, heart, plus, minus, trash, chevron-down, chevron-right, check, x, menu, bell, filter, bike, store, receipt, logout, edit, upload, phone, info, leaf.
  - Original SVG logo (mark + wordmark) `static/img/logo.svg` and `favicon.svg`.
  - Dev-profile-only page `/dev/ui-kit` showing every component and state. MockMvc test that it renders in dev and is 404 in other profiles.

- [x] T05 Base layout, fragments and static pages
  - `layouts/base.html`; header (logo, city label, search box, nav, cart button with count, account menu per role, login/sign-up when anonymous); footer (about, partner links, legal links, copyright).
  - Fragments: flash/toast, form field (label/help/error), pagination, empty state, price, rating pill, veg mark, restaurant card.
  - Mobile header with a drawer (native `<dialog>` or `<details>`), skip-to-content link.
  - htmx loaded from WebJars on every page; CSRF token wired for htmx requests via `static/js/htmx-config.js`; indicator styles disabled.
  - Pages: About, Contact (fictional support email/phone + FAQ), Terms, Privacy, Refund policy, with sensible real copy.
  - Tests: layout renders header/footer, every static page returns 200, security headers present (CSP, X-Content-Type-Options, frame-ancestors).

## Phase 1 - Accounts and security

- [x] T06 User model
  - `User` entity + migration: name, email (unique, stored lower-case), phone (10-digit Indian mobile starting 6-9), password hash, role (CUSTOMER, RESTAURANT_OWNER, DELIVERY_PARTNER, ADMIN), enabled, failed login count, locked-until.
  - Repository with case-insensitive email lookup. Tests.

- [x] T07 Web security configuration
  - Public: home, listings, restaurant pages, search, static assets, uploads, placeholders, login, register, partner-with-us pages, legal pages; in the dev profile only, `/dev/**` (UI kit).
  - Protected: `/account/**`, `/checkout/**`, `/orders/**`, `/pay/**` -> CUSTOMER; `/owner/**` -> RESTAURANT_OWNER; `/partner/**` -> DELIVERY_PARTNER; `/admin/**` -> ADMIN.
  - Custom login page (email, password with show/hide, remember me), POST logout, role-based landing page after login, `UserDetailsService`, disabled users rejected, CSP and security headers from CLAUDE.md.
  - Tests: anonymous redirected to login; each role gets 403 in other roles' areas; login redirects by role.

- [x] T08 Customer registration
  - `/register`: name, email, phone, password + confirm (8+ chars, at least one letter and one digit), accept-terms checkbox. Duplicate email shows an inline error.
  - Auto-login after sign-up, welcome flash. Tests for validation and success.

- [x] T09 Account area and address book
  - `/account`: edit profile, change password (current password required).
  - Address book CRUD: label (Home/Work/Other), house/flat, street/area, landmark, city, 6-digit pincode, default flag. Max 10 per user.
  - Ownership enforced in the service. Tests including another user's address -> 404.

- [x] T10 Forgot and reset password
  - Random token stored hashed, 30-minute expiry, single use. Same response whether or not the email exists.
  - `MailService` interface: `LoggingMailService` (dev/test, logs the reset link) and `SmtpMailService` (mysql profile). Tests.

- [x] T11 Admin bootstrap and login hardening
  - On startup create the admin from `ADMIN_EMAIL` / `ADMIN_PASSWORD` if no admin exists (dev defaults from CLAUDE.md).
  - Lock an account for 15 minutes after 5 failed logins; reset on success; friendly message on the login page. Tests.

## Phase 2 - Catalog: restaurants and menus

- [x] T12 Catalog schema and entities
  - `Cuisine` (name, slug). `Restaurant`: owner (1:1), slug, name, tagline, description, cuisines (M:N), phone, address, area, city, pincode, logo and cover paths, cost for two, minimum order, delivery fee, average prep minutes, opens-at / closes-at, accepting-orders toggle, pure-veg flag, status (PENDING, APPROVED, REJECTED, SUSPENDED) + reason, rating average and count.
  - `MenuCategory` (restaurant, name, sort order). `MenuItem` (restaurant, category, name, description, price, veg, bestseller, spice level 0-3, available, archived, image path, sort order).
  - `Restaurant.isOpenNow(Clock)` handles overnight hours (18:00-02:00). Indexes on status, city, slug, foreign keys. Tests.

- [x] T13 Image storage and placeholders
  - `StorageService`: magic-byte check (jpg/png/webp), 2 MB limit, random names, per-kind folders under `app.upload-dir`, served at `/uploads/**` with cache headers.
  - `PlaceholderController` generating deterministic SVG placeholders `/placeholder/{kind}/{seed}.svg` (colour from a hash of the name, initials or a simple food glyph).
  - Thymeleaf helper picks: uploaded image -> seed image `/img/seed/{slug}.jpg` if the file exists -> placeholder. Tests.

- [x] T14 Demo data seeder
  - Runs when `app.seed-demo=true`, idempotent (restart does not duplicate). Test proves running it twice changes nothing.
  - 10 cuisines. 12 APPROVED fictional restaurants spread across real neighbourhoods of the demo city, distinct cuisines including local specialities, a mix of pure-veg and non-veg, varied hours (one closed at typical demo times, one open overnight).
  - Each restaurant: 3-5 categories, 8-15 items with realistic INR prices and appetising one-line descriptions, some bestsellers, one sold-out item.
  - 1 PENDING restaurant, plus the demo users from CLAUDE.md (owner1 and owner2 each own a restaurant; rider profiles are added in T34).

- [x] T15 Restaurant listing `/restaurants`
  - Card grid: image, name, cuisines, rating pill, delivery time, cost for two, pure-veg badge, "Closed now" overlay with next opening time.
  - Filters: cuisine, pure veg, rating 4.0+, open now. Sort: relevance, rating, fastest, cost low-high, cost high-low. Search by restaurant or dish name. Pagination.
  - Works with plain links/forms; htmx swaps the results and pushes the URL. Only APPROVED restaurants appear. Tests.

- [x] T16 Restaurant page `/r/{slug}`
  - Header: cover, logo, name, cuisines, rating, area, timings, open/closed state, cost for two, delivery time, offers strip.
  - Sticky category navigation, menu sections, item rows (veg mark, bestseller tag, spice level, price, description, image, Add button or quantity stepper - wired in T21), "Pure veg only" toggle, search within menu.
  - Sold-out and archived items handled (sold-out shown disabled, archived hidden). Unknown or non-approved slug -> 404. Tests.

- [x] T17 Search
  - `/search?q=` shows matching restaurants and dishes in two groups; empty and no-result states.
  - Header search suggests the top 5 matches as you type (htmx, debounced, keyboard accessible). Tests.

- [x] T18 Home page `/`
  - Hero with headline and search, cuisine chips linking to filtered listings, rows: "Top rated", "Fastest delivery", "Pure veg picks", how-it-works (3 steps), partner band (restaurants and riders).
  - Looks finished on phone and desktop. Tests.

## Phase 3 - Cart, checkout and orders

- [x] T19 Cart domain
  - `Cart` / `CartItem` tables for logged-in users; session cart for guests merged on login (success handler).
  - One restaurant per cart: adding from another restaurant asks "Start a new cart?" (works without JS).
  - Quantity 1-20. Availability and current price re-checked on every cart view. Tests including merge.

- [x] T20 Pricing service
  - `PriceBreakdown` record: item total, coupon discount, delivery fee (restaurant fee, free above the threshold), platform fee, GST on (item total - discount), grand total.
  - BigDecimal HALF_UP 2dp everywhere. Exhaustive unit tests including edge cases (zero, threshold boundary, discount larger than total).

- [ ] T21 Cart UI
  - Add / increase / decrease / remove via htmx with full-page fallback; header count updates with `hx-swap-oob`.
  - Cart page: items, instructions for the restaurant, bill breakdown, minimum-order progress ("Add ₹80 more"), checkout button; mobile sticky "View cart" bar.
  - Restaurant page steppers wired to the cart. Tests.

- [ ] T22 Coupons
  - `Coupon` entity + migration: code, description, type (PERCENT, FLAT), value, max discount, min order, valid from/until, total limit, per-user limit, first-order-only, optional restaurant, active.
  - Apply/remove in the cart with a specific message for each failure reason. Seeder adds 3 demo coupons. Tests for every rule.

- [ ] T23 Order domain and workflow
  - `Order`: code, customer, restaurant, rider (nullable), status, address snapshot, `OrderItem` snapshots (name, price, qty, veg), price breakdown snapshot, coupon code, payment method (COD, ONLINE), payment status (PENDING, PAID, FAILED, REFUND_PENDING, REFUNDED), 4-digit delivery OTP, estimated delivery time, a timestamp per status, cancel reason and actor, `@Version`.
  - Statuses: PENDING_PAYMENT, PLACED, ACCEPTED, PREPARING, READY_FOR_PICKUP, OUT_FOR_DELIVERY, DELIVERED, CANCELLED, REJECTED.
  - `OrderWorkflow.transition(order, target, actor)` enforces allowed transitions and which role may make each one. Unit tests for every allowed and every forbidden transition.

- [ ] T24 Checkout `/checkout`
  - Pick or add an address inline, delivery instructions, payment method, order summary.
  - Server recomputes everything and validates: restaurant approved, open and accepting; items available; minimum order; coupon still valid.
  - Idempotency key stops duplicate orders on double submit. Order creation and cart clearing in one transaction. Tests.

- [ ] T25 Mock payment
  - `PaymentGateway` interface + `MockPaymentGateway`. Page `/pay/{code}` clearly labelled "TEST MODE - no real money": UPI and Card tabs with format validation, Pay -> success, a "Simulate failure" option.
  - `Payment` entity. Success -> PLACED + PAID. Failure -> retry, switch to COD, or cancel. Unpaid orders expire after 15 minutes (`@Scheduled`, uses Clock).
  - `docs/PAYMENTS.md` explains how a real gateway's test mode would plug in. Tests.

- [ ] T26 Order confirmation, history and detail
  - Confirmation page; `/orders` paginated list with status chips and totals; detail page with items, bill, address and payment.
  - Another customer's order -> 404. Tests.

- [ ] T27 Live order tracking `/orders/{code}/track`
  - Status stepper with timestamps, ETA, restaurant contact, rider name with masked phone once assigned, delivery OTP shown once OUT_FOR_DELIVERY.
  - htmx polling every 10 s that stops at DELIVERED / CANCELLED / REJECTED.
  - Customer can cancel only while PLACED (reason required). Tests.

## Phase 4 - Restaurant owner panel

- [ ] T28 Partner landing and restaurant onboarding
  - `/partner-with-us`: benefits, steps, CTAs for restaurants and riders.
  - `/partner-with-us/restaurant`: one sectioned form creating the owner account and a PENDING restaurant (details, cuisines, timings, fees).
  - Owner dashboard shows a status banner for pending / rejected (with reason) / suspended. Tests.

- [ ] T29 Owner dashboard `/owner`
  - Accepting-orders toggle; today's orders, revenue, average order value, pending count; latest orders.
  - 7-day revenue bar chart rendered as server-side SVG built in Java (no chart library). Tests.

- [ ] T30 Owner menu management `/owner/menu`
  - Categories CRUD + reorder. Items CRUD with image upload, price, veg, bestseller, spice level; availability switch inline (htmx).
  - Items that were ever ordered are archived, not deleted. Another owner's items -> 403/404. Tests.

- [ ] T31 Owner restaurant profile `/owner/restaurant`
  - Edit details, cuisines, timings, fees, minimum order; logo and cover upload with preview. Tests.

- [ ] T32 Owner live orders `/owner/orders`
  - Board with New / Preparing / Ready columns, new orders highlighted, htmx polling every 10 s.
  - Accept with prep-time choice (10/15/20/30/45 min, updates ETA), Reject with reason, Mark preparing, Mark ready - all through OrderWorkflow.
  - Print-friendly order ticket view. Tests.

- [ ] T33 Owner history and reports `/owner/reports`
  - Date range and status filters, paginated table, CSV export generated in Java, top 5 items, revenue summary. Tests.

## Phase 5 - Delivery partner (rider) panel

- [ ] T34 Rider onboarding
  - `/partner-with-us/rider`: account + `DeliveryPartnerProfile` (vehicle type BIKE/SCOOTER/BICYCLE, vehicle number, preferred area, status PENDING/APPROVED/SUSPENDED, online flag).
  - Seeder makes rider1 and rider2 APPROVED. Pending riders see a status banner. Tests.

- [ ] T35 Rider dashboard `/partner`
  - Online/offline toggle. Available orders (ACCEPTED, PREPARING or READY_FOR_PICKUP, unassigned) with restaurant area and payout.
  - Claim uses optimistic locking: if two riders claim at once, one wins and the other sees a friendly message. Max one active delivery.
  - Active delivery card: restaurant address and phone, customer address and masked phone, item count, cash to collect for COD. Tests including the concurrent claim.

- [ ] T36 Delivery flow
  - "Picked up" (only at READY_FOR_PICKUP) -> OUT_FOR_DELIVERY. "Delivered" requires the customer's 4-digit OTP; 5 wrong attempts lock it for 10 minutes.
  - COD orders marked PAID on delivery. Earnings record per delivery (base payout + share of delivery fee, from AppProperties). Tests.

- [ ] T37 Rider earnings `/partner/earnings`
  - Today / week / month totals, delivery history, COD cash in hand. Tests.

## Phase 6 - Admin panel

- [ ] T38 Admin dashboard `/admin`
  - KPIs: orders today, GMV today and 7 days, active restaurants, customers, riders online, pending approvals.
  - Server-side SVG charts: orders per day (14 days), top 5 restaurants by revenue. Recent orders table. Tests.

- [ ] T39 Restaurant moderation `/admin/restaurants`
  - Status filter and search; detail view; approve, reject (reason), suspend (reason), reactivate; owner emailed via MailService. Tests.

- [ ] T40 Riders and users
  - `/admin/riders`: approve / suspend. `/admin/users`: search, enable/disable (never self, never the last admin), view a user's orders. Tests.

- [ ] T41 Coupons and cuisines admin
  - CRUD with validation; usage count per coupon. Tests.

- [ ] T42 Orders admin `/admin/orders`
  - Filters (code, status, restaurant, date range), detail with full timeline, admin cancel with reason (paid online orders -> REFUND_PENDING). Tests.

## Phase 7 - Engagement

- [ ] T43 Ratings and reviews
  - After DELIVERED: one review per order (1-5 stars, optional text up to 500 chars). Restaurant rating updated in the same transaction.
  - Reviews on the restaurant page (paginated, sort newest/highest); owner can reply once; admin can hide a review. Tests.

- [ ] T44 Favourites and reorder
  - Heart a restaurant (htmx), `/account/favourites`.
  - "Reorder" puts available items from a past order into the cart and tells the user about unavailable items or changed prices. Tests.

- [ ] T45 Notifications
  - `Notification` entity, created on order status changes, restaurant/rider approval and similar events.
  - Header bell with unread count (htmx poll every 30 s when logged in), `/notifications` list, mark all read.
  - Emails through MailService for order placed, order delivered, restaurant approved. Tests.

## Phase 8 - REST API for the mobile app

- [ ] T46 API security
  - Second `SecurityFilterChain` for `/api/**`: stateless, CSRF off, JWT bearer through Spring's OAuth2 resource server, RSA-signed tokens (key generated at startup in dev/test, loaded from env in mysql).
  - `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `GET /api/v1/me`. Errors as RFC 9457 ProblemDetail. Web chain unchanged. Tests.

- [ ] T47 Public API
  - Restaurants (filters, sort, paging), restaurant detail + menu, search, cuisines. Java record DTOs only, never entities. Tests.

- [ ] T48 Customer API
  - Addresses CRUD; cart (get/add/update/remove/clear, apply/remove coupon, price breakdown); place order (COD, or ONLINE returning the payment page URL); orders list/detail/tracking; cancel; review.
  - Uses the same services as the website. Tests.

- [ ] T49 Owner and rider API
  - Owner: orders list, accept/reject/preparing/ready, item availability, accepting-orders toggle.
  - Rider: online toggle, available orders, claim, picked up, deliver with OTP, earnings. Tests.

- [ ] T50 API documentation
  - springdoc-openapi at `/swagger-ui.html` with JWT bearer auth, grouped by area, example payloads.
  - `docs/API.md` quick start for the app team (register -> login -> token -> calls). Test that `/v3/api-docs` returns 200.

## Phase 9 - Polish and release

- [ ] T51 Responsive and accessibility audit
  - Review every template for 360 / 768 / 1280 px layouts, focus order, labels, alt text, contrast, keyboard dialogs. Fix everything found and list the fixes in NOTES.md.

- [ ] T52 Dark mode
  - Dark token set via `prefers-color-scheme` plus a manual toggle stored in a cookie (server renders `data-theme`). Contrast checked on every page.

- [ ] T53 SEO and sharing
  - Per-page title and meta description, Open Graph tags (restaurant pages use their image), canonical URLs, `sitemap.xml` and `robots.txt` controllers, PNG favicon and touch icons. Tests.

- [ ] T54 Performance
  - Assert query counts on the listing and restaurant pages (Hibernate statistics in a test) and fix any N+1.
  - Missing indexes via a new migration; Spring resource chain with content-hash URLs and long cache headers for static assets. Tests.

- [ ] T55 Security review
  - IDOR test for every owner, rider and customer resource; rate-limit OTP and password-reset endpoints; verify CSP and headers; upload hardening; secure cookie flags in mysql; no secrets in the repo. Fix all findings.

- [ ] T56 MySQL readiness
  - Review every migration for MySQL 8 compatibility. Add `docker-compose.yml` (MySQL 8) as an option.
  - `docs/SETUP-MYSQL.md` for Windows: install MySQL, create database and user, env vars, run with the `mysql` profile, seed toggle. Note in NOTES.md that this needs one manual run by a human.

- [ ] T57 Documentation
  - README.md: overview, features per role, tech stack, architecture (Mermaid), Windows setup, demo accounts, project structure, `docs/screenshots/` section, team section placeholder.
  - `docs/ARCHITECTURE.md`, `docs/DATABASE.md` (Mermaid ER diagram + table descriptions), `docs/ROUTES.md` (every web route and API endpoint with its role).

- [ ] T58 Final QA
  - Full build green. No TODO / FIXME / lorem / "coming soon" left. Every route in `docs/ROUTES.md` is covered by at least one test. Consistent copy and naming across pages. PLAN.md reconciled.

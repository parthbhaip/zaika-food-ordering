# Zaika API quick start

The REST API behind the Zaika mobile apps. It runs inside the same Spring Boot application as the website and uses the
same services, so prices, rules and messages are identical: anything the app can do, the website does the same way.

- **Interactive docs (Swagger UI):** `/swagger-ui.html`, grouped by area (Account, Catalog, Customer, Restaurant
  owner, Delivery partner), with an example body for every request. Press **Authorize**, paste a token and use
  **Try it out**.
- **OpenAPI 3 description:** `/v3/api-docs` (everything) or `/v3/api-docs/{group}` with group `account`, `catalog`,
  `customer`, `owner` or `rider`. Feed it to a client generator (OpenAPI Generator, Swift/Kotlin) for typed models.
- **Base URL:** `http://localhost:8080` on a developer machine (dev profile); on a server, the site's public address
  (`APP_BASE_URL`). Every endpoint lives under `/api/v1`.

The examples below use `curl` against the dev profile, where the demo data is seeded (Kolhapur restaurants and the
demo accounts, password `Demo@1234`: `customer@zaika.test`, `owner1@zaika.test` for Rassa Darbar, `rider1@zaika.test`).
Demo accounts never exist on a production database.

## 1. Sign up (new customers)

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name": "Riya Shinde", "email": "riya.shinde@example.com", "phone": "9823456789",
       "password": "Kolhapur@2026", "acceptTerms": true}'
```

`201 Created` signs the new customer in straight away and answers the same body as a sign-in (step 2). The website's
rules apply: a 10-digit Indian mobile number starting with 6-9, a password of 8+ characters with a letter and a digit,
accepted terms. Invalid fields come back together:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Some fields need fixing. See errors for each field.",
  "instance": "/api/v1/auth/register",
  "errors": {
    "phone": "Enter a 10-digit mobile number starting with 6, 7, 8 or 9.",
    "acceptTerms": "Please accept the terms to create your account."
  }
}
```

A taken email is `409` with `errors.email`. Only customers sign up through the API; restaurants and delivery partners
join on the website (`/partner-with-us`) and are approved by Zaika before they can work.

## 2. Sign in

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "customer@zaika.test", "password": "Demo@1234"}'
```

```json
{
  "accessToken": "eyJraWQiOi...",
  "tokenType": "Bearer",
  "expiresIn": 604800,
  "expiresAt": "2026-10-11T14:00:00Z",
  "user": { "id": 6, "name": "Riya Shinde", "email": "customer@zaika.test", "phone": "9890011230", "role": "CUSTOMER" }
}
```

Every role signs in here; `user.role` (`CUSTOMER`, `RESTAURANT_OWNER`, `DELIVERY_PARTNER`) tells the app which screens
to show. Refusals:

| Status | When |
|---|---|
| 401 | Wrong email or password. |
| 429 | Five wrong passwords in a row: sign-in is paused for 15 minutes. `lockedUntil` and the `Retry-After` header say until when (the same lock as the website). |
| 403 | The account is switched off by Zaika (only said after the right password). |

## 3. Call with the token

Send the token on every call as a bearer token:

```bash
TOKEN="eyJraWQiOi..."   # accessToken from step 1 or 2
curl http://localhost:8080/api/v1/me -H "Authorization: Bearer $TOKEN"
```

- Tokens last **7 days** (`expiresIn` seconds; `expiresAt` is in UTC). There is no refresh token: when any call
  answers `401`, send the user to sign in again. A token also stops working early when the password changes or Zaika switches the account off.
- The catalog needs no token. Addresses, cart and orders need a `CUSTOMER` token, `/api/v1/owner/**` a
  `RESTAURANT_OWNER` token and `/api/v1/rider/**` a `DELIVERY_PARTNER` token; another role's token gets `403`.
- Tokens are signed with the server's RSA key (`JWT_PRIVATE_KEY` in production). Without that setting a key is made
  at startup, so tokens from a dev server stop working after it restarts.
- The API is stateless: no cookies, no sessions, no CSRF token.

## 4. A customer's first order

**Browse.** `GET /api/v1/restaurants?open=true&sort=rating` lists approved restaurants, 12 a page; `GET
/api/v1/restaurants/rassa-darbar` answers one restaurant with its menu (`sections[].items[]`, each dish with its `id`,
`price`, `veg` and `available`). `GET /api/v1/search?q=misal` finds restaurants and dishes; `GET /api/v1/cuisines`
lists the cuisine slugs for the `cuisine` filter.

**Fill the cart.** Put two of a dish in the cart by its menu `id` (quantity 0 removes it):

```bash
curl -X PUT http://localhost:8080/api/v1/cart/items/1 -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"quantity": 2}'
curl -X POST http://localhost:8080/api/v1/cart/coupon -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"code": "WELCOME50"}'
```

Every cart call answers the whole cart, re-checked against the menu. Two Mutton Thalis with `WELCOME50` (half off a
first order, at most ₹100):

```json
{
  "restaurant": { "slug": "rassa-darbar", "name": "Rassa Darbar", "minimumOrder": 199.00 },
  "takingOrders": true,
  "lines": [ { "itemId": 1, "name": "Mutton Thali", "veg": false, "price": 420.00, "quantity": 2, "lineTotal": 840.00,
               "imageUrl": "http://localhost:8080/img/seed/dish/mutton-thali.jpg" } ],
  "itemCount": 2,
  "notices": [],
  "coupon": { "code": "WELCOME50", "headline": "50% off up to ₹100", "discount": 100.00 },
  "bill": { "itemTotal": 840.00, "discount": 100.00, "deliveryFee": 0.00, "platformFee": 5.00, "gst": 37.00,
            "grandTotal": 782.00 },
  "freeDeliveryGap": null,
  "canCheckout": true
}
```

(Trimmed: the real answer also has `opensAt`, `instructions`, `couponNotice`, `offers` and `minimumOrderShortfall`.)
A cart holds one restaurant's dishes: adding another restaurant's dish answers `409` with `"reason":
"OTHER_RESTAURANT"`; send `"startNew": true` to empty the cart and start over.

**Pick an address.** `GET /api/v1/addresses` lists the saved ones (default first); add one with:

```bash
curl -X POST http://localhost:8080/api/v1/addresses -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"label": "HOME", "house": "Flat 3, Sai Residency", "street": "8th Lane, Rajarampuri",
       "landmark": "Near Rajarampuri bus stop", "city": "Kolhapur", "pincode": "416008", "makeDefault": true}'
```

**Place the order** with the total the customer saw and a fresh UUID:

```bash
curl -X POST http://localhost:8080/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"addressId": 12, "paymentMethod": "COD", "deliveryNote": "Ring the bell twice.",
       "idempotencyKey": "3f2b8c1e-4d5a-4b6c-9d7e-8f9a0b1c2d3e", "expectedTotal": 782.00}'
```

- `201 Created` with the order (`code` like `ZK-26-000123`, `status`, dishes, bill, address).
- `idempotencyKey`: make a new random UUID for each checkout and send the **same** one again if the network fails;
  a repeat answers `200` with the order already placed, never a second order.
- `expectedTotal` is the cart's `bill.grandTotal`. If the price, coupon or kitchen changed since, the answer is `409`
  with `checkCart: true`: reload the cart, show the new total and ask again.
- `paymentMethod: "ONLINE"` answers `paymentUrl`: open it in a browser (it is the website's payment page, clearly
  marked TEST MODE, no real money) and pay before `payBy` (15 minutes), or the order is cancelled.

**Track it.** `GET /api/v1/orders/ZK-26-000123/tracking` answers the steps, the ETA, the rider's name and masked phone,
and `deliveryOtp`, the 4-digit code the customer gives the rider (shown only while the order is out for delivery).
Poll every 10-15 seconds while `live` is true; `version` changes whenever anything about the order does. While the
order is `PLACED` it can be cancelled with one of `cancelReasons`:

```bash
curl -X POST http://localhost:8080/api/v1/orders/ZK-26-000123/cancel -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"reason": "Ordered by mistake"}'
```

Once delivered, `POST /api/v1/orders/{code}/review` with `{"stars": 5, "comment": "..."}` rates it (once).

Order statuses: `PENDING_PAYMENT` (online, not paid yet), `PLACED`, `ACCEPTED`, `PREPARING`, `READY_FOR_PICKUP`,
`OUT_FOR_DELIVERY`, `DELIVERED`, `CANCELLED`, `REJECTED`. Every answer also has `statusLabel`, ready to show.

## 5. Restaurant owner app

Sign in as an owner (`owner1@zaika.test`), then:

```bash
curl http://localhost:8080/api/v1/owner/orders -H "Authorization: Bearer $OWNER_TOKEN"
curl -X POST http://localhost:8080/api/v1/owner/orders/ZK-26-000123/accept -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" -d '{"prepMinutes": 20}'
curl -X POST http://localhost:8080/api/v1/owner/orders/ZK-26-000123/preparing -H "Authorization: Bearer $OWNER_TOKEN"
curl -X POST http://localhost:8080/api/v1/owner/orders/ZK-26-000123/ready -H "Authorization: Bearer $OWNER_TOKEN"
```

The board lists new, accepted, preparing and ready orders oldest first, with `prepChoices` for accepting and
`rejectReasons` for rejecting (`details` is required for "Something else"); poll it and compare `version`. An order
that moved on meanwhile (the customer cancelled, a double tap) answers `409` with its `status` now. `PUT
/api/v1/owner/menu/items/{itemId}/availability` with `{"available": false}` marks a dish sold out; `PUT
/api/v1/owner/accepting-orders` with `{"acceptingOrders": false}` pauses new orders.

## 6. Delivery partner app

Sign in as a rider (`rider1@zaika.test`), then go online, claim an order, pick it up and deliver it with the customer's
code:

```bash
curl -X PUT http://localhost:8080/api/v1/rider/online -H "Authorization: Bearer $RIDER_TOKEN" \
  -H "Content-Type: application/json" -d '{"online": true}'
curl http://localhost:8080/api/v1/rider/orders/available -H "Authorization: Bearer $RIDER_TOKEN"
curl -X POST http://localhost:8080/api/v1/rider/orders/ZK-26-000123/claim -H "Authorization: Bearer $RIDER_TOKEN"
curl -X POST http://localhost:8080/api/v1/rider/orders/ZK-26-000123/pickup -H "Authorization: Bearer $RIDER_TOKEN"
curl -X POST http://localhost:8080/api/v1/rider/orders/ZK-26-000123/deliver -H "Authorization: Bearer $RIDER_TOKEN" \
  -H "Content-Type: application/json" -d '{"otp": "4821"}'
```

A rider carries one delivery at a time; `GET /api/v1/rider/dashboard` shows it as `currentDelivery` with the pick-up
and drop details and `cashToCollect` for cash orders. Claiming an order someone else just took is a `409`. A wrong code
is a `400` with `errors.otp`; five wrong codes in a row lock it for 10 minutes. `GET /api/v1/rider/earnings` answers
today's, this week's and this month's totals and the delivery history.

## Conventions

- **Errors** are RFC 9457 problem details (`Content-Type: application/problem+json`): show `detail` to the user.
  `400` = invalid input (`errors` maps each field or query parameter to a message), `401` = no valid token, `403` =
  wrong role, `404` = not found, which is also the answer for someone else's address or order, `409` = refused by
  the rules (sold out, kitchen closed, too late to cancel...), often with a `reason` code for the app.
- **Money** is a JSON number in rupees with 2 decimals (`782.00`). The server always prices the order itself.
- **Times** are ISO 8601 with the India offset (`2026-10-04T19:30:00+05:30`); opening hours are `"HH:mm"`.
- **Pages** start at 1: `?page=2`. List answers carry `items`, `page`, `size`, `totalItems` and `totalPages`; a page
  past the end is an empty `items`.
- **Images** are absolute URLs. **Phone numbers** of other people are masked (`+91 98••• ••210`).
- **Polling:** the tracking, the owner's board and the rider's dashboard carry a `version`; poll every 10-15 seconds
  and redraw only when it changes.

## Endpoints

| Method and path | Token | What it does |
|---|---|---|
| `POST /api/v1/auth/register` | none | Sign up as a customer (signed in at once) |
| `POST /api/v1/auth/login` | none | Sign in, any role |
| `GET /api/v1/me` | any | The signed-in account |
| `GET /api/v1/cuisines` | none | Every cuisine |
| `GET /api/v1/restaurants` | none | Restaurants: `q`, `cuisine`, `veg`, `top`, `open`, `sort`, `page` |
| `GET /api/v1/restaurants/{slug}` | none | One restaurant with its menu |
| `GET /api/v1/search` | none | Restaurants and dishes matching `q` |
| `GET /api/v1/addresses` | customer | Saved addresses |
| `POST /api/v1/addresses` | customer | Add an address (up to 10) |
| `GET /api/v1/addresses/{id}` | customer | One address |
| `PUT /api/v1/addresses/{id}` | customer | Change an address |
| `DELETE /api/v1/addresses/{id}` | customer | Delete an address |
| `GET /api/v1/cart` | customer | The cart and its bill |
| `DELETE /api/v1/cart` | customer | Empty the cart |
| `PUT /api/v1/cart/items/{itemId}` | customer | Add a dish or set its quantity |
| `DELETE /api/v1/cart/items/{itemId}` | customer | Remove a dish |
| `PUT /api/v1/cart/note` | customer | Note for the kitchen |
| `POST /api/v1/cart/coupon` | customer | Apply a coupon |
| `DELETE /api/v1/cart/coupon` | customer | Remove the coupon |
| `POST /api/v1/orders` | customer | Place the cart as an order |
| `GET /api/v1/orders` | customer | Order history, 10 a page |
| `GET /api/v1/orders/{code}` | customer | One order |
| `GET /api/v1/orders/{code}/tracking` | customer | Live tracking |
| `POST /api/v1/orders/{code}/cancel` | customer | Cancel while the restaurant has not accepted it |
| `POST /api/v1/orders/{code}/review` | customer | Rate a delivered order |
| `GET /api/v1/owner/orders` | owner | Live orders board |
| `GET /api/v1/owner/orders/{code}` | owner | Kitchen ticket |
| `POST /api/v1/owner/orders/{code}/accept` | owner | Accept with a preparation time |
| `POST /api/v1/owner/orders/{code}/reject` | owner | Reject with a reason |
| `POST /api/v1/owner/orders/{code}/preparing` | owner | Start preparing |
| `POST /api/v1/owner/orders/{code}/ready` | owner | Ready for pick-up |
| `GET /api/v1/owner/menu` | owner | The menu with availability |
| `PUT /api/v1/owner/menu/items/{itemId}/availability` | owner | Sold out or back in stock |
| `PUT /api/v1/owner/accepting-orders` | owner | Pause or resume new orders |
| `GET /api/v1/rider/dashboard` | rider | Status, current delivery, waiting orders |
| `GET /api/v1/rider/orders/available` | rider | Orders waiting for a rider |
| `PUT /api/v1/rider/online` | rider | Go online or offline |
| `POST /api/v1/rider/orders/{code}/claim` | rider | Claim a waiting order |
| `POST /api/v1/rider/orders/{code}/pickup` | rider | Picked up |
| `POST /api/v1/rider/orders/{code}/deliver` | rider | Delivered, with the customer's code |
| `GET /api/v1/rider/earnings` | rider | Totals and delivery history, 20 a page |

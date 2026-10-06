# Payments

Zaika takes online payments through a **mock gateway**. No money moves, no bank or payment company is contacted, and
the payment page says so in a banner: "TEST MODE - no real money". This document explains how the pieces fit and how a
real gateway's test mode would replace the mock.

## How an online order is paid today

1. Checkout (`order.CheckoutService`) saves an online order as `PENDING_PAYMENT` with payment status `PENDING` and
   sends the customer to `/pay/{code}`. The restaurant does not see the order yet.
2. The payment page (`payment.PaymentController`, `templates/payment/pay.html`) has two tabs, UPI and Card. The fields
   are checked for format only (`payment.PaymentForm`): a UPI ID like `name@bank`; a card number that passes the Luhn
   check, an expiry `MM/YY` that has not passed, a 3 or 4 digit CVV and a name. Any valid-looking value works, for
   example `riya@upi` or the card `4111 1111 1111 1111`, any future expiry, any CVV.
3. `payment.PaymentService.pay` locks the order row and calls `PaymentGateway.charge`. The mock
   (`payment.MockPaymentGateway`) approves every charge with a random `MOCK-...` reference, unless the customer pressed
   **Simulate failure**, in which case it declines.
4. Every attempt is stored as a `payment.Payment` row (`payments` table): gateway, channel, amount, outcome, the
   gateway's reference or the decline reason, and a masked instrument (`Card ending 1111`, `ri••@upi`). Card numbers,
   CVVs and full UPI IDs are never stored or logged.
5. Approved: `OrderWorkflow.confirmPayment` moves the order to `PLACED` (actor: system) and marks it `PAID`; the
   customer lands on `/orders/{code}`. Declined: the order stays `PENDING_PAYMENT` with payment status `FAILED`, and
   the page explains why and offers three ways on: try again, pay cash on delivery instead (`POST /pay/{code}/cash`,
   the order becomes a cash order and is `PLACED`), or cancel (`POST /pay/{code}/cancel`).
6. An order that is still unpaid 15 minutes after checkout is cancelled by the system with the reason "Not paid within
   15 minutes". `PaymentService.expireUnpaid()` runs every minute (`@Scheduled`, cron `app.payments.expiry-cron`) and
   reads the time from the app `Clock`; the payment page also expires an overdue order on the spot, so a late "Pay"
   never goes through. Cancelling or expiring gives the order's coupon use back (`CouponService.release`).

Every payment action takes a row lock on the order, so a payment, a cancel and the expiry job can never act on the same
order at the same time: whichever comes first wins, and the others see an order that has moved on.

## Plugging in a real gateway's test mode

The rest of the app only talks to the `PaymentGateway` interface:

```java
public interface PaymentGateway {
    String name();                  // stored with each payment, e.g. "MOCK"
    Result charge(Charge charge);   // approved with a reference, or declined with a reason
}
```

A real provider (any Indian payment gateway with a sandbox) would be wired like this:

1. **Keys from the environment.** Add `PAYMENT_KEY_ID` / `PAYMENT_KEY_SECRET` / `PAYMENT_WEBHOOK_SECRET` to
   `application-mysql.properties` as `${...}` placeholders and to `.env.example`. Use the provider's *test* keys; they
   only ever reach the sandbox. Never commit them.
2. **Card details never touch Zaika.** With a real gateway the payment page would not post card numbers to our server.
   Instead it would load the provider's own checkout widget or hosted page (allowed in the CSP for that one page), and
   our server would only see an opaque payment id. `PaymentForm` and its format checks are only for the mock.
3. **Create, then confirm.** Split `charge` in two: before showing the page, create a provider order for
   `order.getBill().grandTotal()` with our order code as the receipt; after the widget finishes, verify the provider's
   signature on the result and fetch the payment's status from the provider's API. Only a verified "captured" status
   counts as paid.
4. **Webhooks as the source of truth.** Add a `POST /payments/webhook` endpoint (CSRF exempt, permitted in
   `SecurityConfig`, signature checked with the webhook secret) that calls the same `PaymentService` code as the page:
   lock the order, record the `Payment`, and `confirmPayment` if it is still `PENDING_PAYMENT`. The page redirect and
   the webhook may both arrive; the row lock and the status check make the second one a no-op.
5. **Late payments and refunds.** If a payment is captured after the 15-minute expiry has cancelled the order, the
   order stays cancelled and the payment must be refunded through the provider's refund API. Paid orders that are
   later cancelled or rejected are already marked `REFUND_PENDING` by `OrderWorkflow`; a refund job would call the
   provider and then mark them `REFUNDED`.
6. **Test mode.** Keep the banner and the "TEST MODE - no real money" label while sandbox keys are in use. Sandboxes
   simulate declines with special test card numbers or UPI IDs from the provider's documentation, which replaces the
   mock's "Simulate failure" button.

Swapping gateways is then a Spring bean choice: annotate the real implementation with
`@ConditionalOnProperty("app.payments.gateway", havingValue = "...")` and the mock with the opposite, so dev and the
tests keep using the mock.

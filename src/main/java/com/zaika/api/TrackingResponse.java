package com.zaika.api;

import static com.zaika.api.OrderResponse.at;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import com.zaika.common.Formatters;
import com.zaika.order.CancelOrderForm;
import com.zaika.order.Order;
import com.zaika.order.OrderStatus;
import com.zaika.order.OrderTrackingService.Step;
import com.zaika.order.OrderTrackingService.Tracking;
import com.zaika.user.User;

/**
 * Live tracking of an order, as the website's tracking page shows it. Poll it while {@code live} (every 10 seconds is
 * plenty); {@code version} changes whenever the order does.
 *
 * @param headline the big line at the top ("Your food is being prepared")
 * @param late live and past its estimated delivery time
 * @param steps the progress list: the whole journey for a moving or delivered order; for a cancelled or rejected
 *        one the steps it reached and then how it ended (state FAILED, note = the reason)
 * @param rider the delivery partner once one is assigned (phone masked), else null
 * @param deliveryOtp the 4-digit code to tell the rider at the door; only while the order is out for delivery
 * @param cancelReasons the reasons {@code POST /api/v1/orders/{code}/cancel} accepts, while it can be cancelled
 */
public record TrackingResponse(String code, OrderStatus status, String headline, boolean live, boolean late,
		OffsetDateTime estimatedDeliveryAt, List<StepResponse> steps, Rider rider, String deliveryOtp,
		boolean canCancel, List<String> cancelReasons, String endedBy, long version) {

	static TrackingResponse of(Tracking t, Formatters fmt, ZoneId zone) {
		Order o = t.order();
		User rider = o.getRider();
		return new TrackingResponse(o.getCode(), o.getStatus(), t.headline(), t.live(), t.late(),
				at(o.getEstimatedDeliveryAt(), zone),
				t.steps().stream().map(step -> StepResponse.of(step, zone)).toList(),
				rider == null ? null : new Rider(rider.getName(), fmt.maskedPhone(rider.getPhone())),
				t.showOtp() ? o.getDeliveryOtp() : null, t.canCancel(),
				t.canCancel() ? CancelOrderForm.REASONS : List.of(), t.endedBy(), o.getVersion());
	}

	/** One line of the progress list; {@code at} is null for steps still to come. */
	public record StepResponse(String label, String note, OffsetDateTime at, Step.State state) {

		static StepResponse of(Step step, ZoneId zone) {
			return new StepResponse(step.label(), step.note(), OrderResponse.at(step.at(), zone), step.state());
		}

	}

	/** The delivery partner: name and a masked phone ("+91 98••• ••345"). */
	public record Rider(String name, String phone) {
	}

}

package com.zaika.api;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.owner.OwnerDashboardService.AcceptingOrders;
import com.zaika.owner.OwnerOrdersService;
import com.zaika.owner.OwnerOrdersService.Board;
import com.zaika.owner.RejectOrderForm;

/**
 * The owner's live orders, as the website's board shows them: every order the kitchen still has to deal with
 * (PLACED to accept or reject, ACCEPTED / PREPARING on the stove, READY_FOR_PICKUP waiting for the rider), oldest
 * first. Poll it every 10 seconds or so; {@code version} changes whenever anything on it does.
 *
 * @param prepChoices the {@code prepMinutes} values accept takes
 * @param rejectReasons the {@code reason} values reject takes ({@code details} required for "Something else")
 */
public record OwnerBoardResponse(Restaurant restaurant, Accepting accepting, List<OwnerOrderResponse> orders,
		String version, List<Integer> prepChoices, List<String> rejectReasons) {

	static OwnerBoardResponse of(Board board, Clock clock) {
		com.zaika.catalog.Restaurant r = board.restaurant();
		LocalDateTime now = LocalDateTime.now(clock);
		return new OwnerBoardResponse(new Restaurant(r.getSlug(), r.getName(), r.getStatus()),
				new Accepting(r.isAcceptingOrders(), r.isOpenNow(clock), r.getOpensAt()),
				board.orders().stream().map(o -> OwnerOrderResponse.of(o, now, clock.getZone())).toList(),
				board.version(), OwnerOrdersService.PREP_CHOICES, RejectOrderForm.REASONS);
	}

	/** The owner's restaurant; only an APPROVED one is listed and gets orders. */
	public record Restaurant(String slug, String name, RestaurantStatus status) {
	}

	/**
	 * The accepting-orders switch.
	 *
	 * @param acceptingOrders new orders are on (off = paused by the owner)
	 * @param openNow within its opening hours right now
	 * @param opensAt the daily opening time ("HH:mm")
	 */
	public record Accepting(boolean acceptingOrders, boolean openNow, @JsonFormat(pattern = "HH:mm") LocalTime opensAt) {

		static Accepting of(AcceptingOrders a) {
			return new Accepting(a.on(), a.openNow(), a.opensAt());
		}

	}

}

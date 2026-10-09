package com.zaika.owner;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.order.Order;
import com.zaika.order.OrderRepository;
import com.zaika.order.OrderRepository.DishSales;
import com.zaika.order.OrderRepository.StatusTotal;
import com.zaika.order.OrderStatus;
import com.zaika.order.PaymentMethod;

/**
 * The owner's history and reports for a range of days: a revenue summary, the five best-selling dishes, a page of the
 * orders and the same orders as a CSV file. Always the signed-in owner's own restaurant (looked up by owner id).
 * <p>
 * Orders count by the day they were placed (Asia/Kolkata, from the injected Clock); orders that never reached the
 * kitchen (unpaid online orders) never show. As on the dashboard, revenue is the food value (item total, before
 * coupons, fees and GST) of orders that were not cancelled or rejected. The summary and top dishes cover the whole
 * range; the status filter narrows the table and the CSV.
 */
@Service
public class OwnerReportsService {

	/** Orders per table page. */
	public static final int PAGE_SIZE = 20;

	/** Dishes in the best-sellers list. */
	public static final int TOP_DISHES = 5;

	private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private final RestaurantRepository restaurants;

	private final OrderRepository orders;

	private final Clock clock;

	OwnerReportsService(RestaurantRepository restaurants, OrderRepository orders, Clock clock) {
		this.restaurants = restaurants;
		this.orders = orders;
		this.clock = clock;
	}

	/**
	 * The report of the owner's restaurant for {@code filter} (normalized first), or empty if this owner has none.
	 * Five or six queries whatever the volume.
	 */
	@Transactional(readOnly = true)
	public Optional<Report> report(long ownerId, ReportFilter filter) {
		boolean cut = filter.normalize(LocalDate.now(clock));
		return restaurants.findByOwnerId(ownerId).map(restaurant -> {
			LocalDateTime from = filter.getFrom().atStartOfDay();
			LocalDateTime to = filter.getTo().plusDays(1).atStartOfDay();
			long id = restaurant.getId();
			Summary summary = Summary.of(orders.totalsByStatus(id, from, to));
			List<DishSales> top = orders.findTopDishes(id, OrderStatus.PLACED_AND_LIVE, from, to,
					PageRequest.of(0, TOP_DISHES));
			Page<Order> page = orders.findReport(id, statuses(filter), from, to,
					PageRequest.of(filter.getPage() - 1, PAGE_SIZE));
			if (page.hasContent()) {
				orders.fetchItems(page.map(Order::getId).toList());
			}
			return new Report(restaurant, filter, cut, summary, top, page);
		});
	}

	/** Every order of the report (all pages) as a CSV file, or empty if this owner has no restaurant. */
	@Transactional(readOnly = true)
	public Optional<Export> export(long ownerId, ReportFilter filter) {
		filter.normalize(LocalDate.now(clock));
		return restaurants.findByOwnerId(ownerId).map(restaurant -> {
			List<Order> rows = orders.findReportRows(restaurant.getId(), statuses(filter),
					filter.getFrom().atStartOfDay(), filter.getTo().plusDays(1).atStartOfDay());
			String name = "zaika-orders-" + restaurant.getSlug() + "-" + filter.getFrom() + "-to-" + filter.getTo()
					+ (filter.getStatus() == null ? "" : "-" + filter.getStatus()) + ".csv";
			return new Export(name, csv(rows));
		});
	}

	private static Collection<OrderStatus> statuses(ReportFilter filter) {
		return filter.getStatusOption() == null ? ReportFilter.STATUSES : List.of(filter.getStatusOption());
	}

	/** The orders as CSV (RFC 4180: comma separated, CRLF line ends), money as plain numbers with two decimals. */
	static String csv(List<Order> rows) {
		StringBuilder csv = new StringBuilder();
		line(csv, "Order", "Placed", "Status", "Customer", "Dishes", "Items", "Payment", "Payment status",
				"Food value", "Discount", "Delivery fee", "Platform fee", "GST", "Order total", "Coupon", "Reason");
		for (Order o : rows) {
			var bill = o.getBill();
			line(csv, o.getCode(), CSV_TIME.format(o.timestampOf(OrderStatus.PLACED)), o.getStatus().getLabel(),
					o.getCustomer().getName(),
					o.getItems().stream().map(i -> i.getQuantity() + " x " + i.getName()).collect(Collectors.joining("; ")),
					String.valueOf(o.getItemCount()),
					o.getPaymentMethod() == PaymentMethod.COD ? "Cash on delivery" : "Online",
					o.getPaymentStatus().getLabel(), amount(bill.itemTotal()), amount(bill.discount()),
					amount(bill.deliveryFee()), amount(bill.platformFee()), amount(bill.gst()),
					amount(bill.grandTotal()), o.getCouponCode(), o.getCancelReason());
		}
		return csv.toString();
	}

	private static void line(StringBuilder csv, String... cells) {
		for (int n = 0; n < cells.length; n++) {
			csv.append(n == 0 ? "" : ",").append(cell(cells[n]));
		}
		csv.append("\r\n");
	}

	private static String amount(BigDecimal value) {
		return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

	/**
	 * One CSV cell. Quoted when it holds a comma, quote or line break (quotes doubled). Text a spreadsheet would run
	 * as a formula (starting with tab or carriage return, or with =, +, - or @ after any leading whitespace) gets a
	 * leading apostrophe, so a dish or customer name can never run code in the owner's spreadsheet. Null is an empty
	 * cell.
	 */
	static String cell(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		String lead = value.stripLeading();
		boolean formula = "\t\r".indexOf(value.charAt(0)) >= 0
				|| !lead.isEmpty() && "=+-@".indexOf(lead.charAt(0)) >= 0;
		String text = formula ? "'" + value : value;
		return text.matches("(?s).*[\",\r\n].*") ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
	}

	/** Everything on the reports page. {@code orders} has its dishes loaded; {@code cut}: the range was cut to a year. */
	public record Report(Restaurant restaurant, ReportFilter filter, boolean cut, Summary summary,
			List<DishSales> topDishes, Page<Order> orders) {
	}

	/**
	 * The range in numbers: orders that count as sales and their food value, and orders the kitchen saw that were
	 * cancelled or rejected (missed) with the food value they would have brought.
	 */
	public record Summary(long orders, BigDecimal revenue, long missed, BigDecimal missedValue) {

		static Summary of(List<StatusTotal> totals) {
			long sold = 0;
			long missed = 0;
			BigDecimal revenue = BigDecimal.ZERO;
			BigDecimal lost = BigDecimal.ZERO;
			for (StatusTotal t : totals) {
				if (OrderStatus.PLACED_AND_LIVE.contains(t.status())) {
					sold += t.orders();
					revenue = revenue.add(t.itemTotal());
				}
				else if (t.status() == OrderStatus.CANCELLED || t.status() == OrderStatus.REJECTED) {
					missed += t.orders();
					lost = lost.add(t.itemTotal());
				}
			}
			return new Summary(sold, revenue.setScale(2, RoundingMode.HALF_UP), missed,
					lost.setScale(2, RoundingMode.HALF_UP));
		}

		/** Revenue per order, to the paisa; zero without orders. */
		public BigDecimal averageOrderValue() {
			return orders == 0 ? BigDecimal.ZERO.setScale(2)
					: revenue.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP);
		}

	}

	/** A CSV download: file name and content. */
	public record Export(String filename, String content) {
	}

}

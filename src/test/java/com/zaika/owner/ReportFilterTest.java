package com.zaika.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.zaika.order.OrderStatus;
import com.zaika.owner.ReportFilter.Preset;

/** T33: the reports filter's defaults, range rules, status slugs and canonical URLs. */
class ReportFilterTest {

	static final LocalDate TODAY = LocalDate.of(2026, 10, 4);

	ReportFilter filter(LocalDate from, LocalDate to) {
		ReportFilter f = new ReportFilter();
		f.setFrom(from);
		f.setTo(to);
		return f;
	}

	@Test
	void defaultsAreTheLast30Days() {
		ReportFilter f = new ReportFilter();
		assertThat(f.normalize(TODAY)).isFalse();
		assertThat(f.getFrom()).isEqualTo(LocalDate.of(2026, 9, 5));
		assertThat(f.getTo()).isEqualTo(TODAY);
		assertThat(f.days()).isEqualTo(30);
		assertThat(f.url()).isEqualTo("/owner/reports");
		assertThat(f.csvUrl()).isEqualTo("/owner/reports/orders.csv");
	}

	@Test
	void rangesAreCompletedSwappedAndCutToAYear() {
		ReportFilter future = filter(LocalDate.of(2026, 10, 10), null);
		future.normalize(TODAY);
		assertThat(future.url()).isEqualTo("/owner/reports?from=2026-10-10&to=2026-10-10");

		ReportFilter swapped = filter(TODAY, LocalDate.of(2026, 9, 1));
		assertThat(swapped.normalize(TODAY)).isFalse();
		assertThat(swapped.url()).isEqualTo("/owner/reports?from=2026-09-01&to=2026-10-04");

		ReportFilter year = filter(LocalDate.of(2025, 10, 4), TODAY);
		assertThat(year.normalize(TODAY)).isFalse();
		assertThat(year.days()).isEqualTo(ReportFilter.MAX_DAYS);

		ReportFilter tooLong = filter(LocalDate.of(2025, 10, 3), TODAY);
		assertThat(tooLong.normalize(TODAY)).isTrue();
		assertThat(tooLong.getFrom()).isEqualTo(LocalDate.of(2025, 10, 4));
	}

	@Test
	void statusesUseSlugsAndSkipUnpaidOrders() {
		ReportFilter f = new ReportFilter();
		f.setStatus(" Out_For_Delivery ");
		assertThat(f.getStatusOption()).isEqualTo(OrderStatus.OUT_FOR_DELIVERY);
		assertThat(f.getStatus()).isEqualTo("out-for-delivery");
		f.setStatus("pending-payment");
		assertThat(f.getStatusOption()).isNull();
		assertThat(ReportFilter.STATUSES).hasSize(8).doesNotContain(OrderStatus.PENDING_PAYMENT);

		f.setStatus("cancelled");
		f.setPage(3);
		f.normalize(TODAY);
		assertThat(f.url()).isEqualTo("/owner/reports?status=cancelled&page=3");
		assertThat(f.csvUrl()).isEqualTo("/owner/reports/orders.csv?status=cancelled");
	}

	@Test
	void presetsKeepTheStatusAndMarkTheCurrentRange() {
		ReportFilter f = filter(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1));
		f.setStatus("delivered");
		f.normalize(LocalDate.of(2026, 1, 1));
		assertThat(f.presets()).extracting(Preset::label, Preset::url, Preset::active).containsExactly(
				tuple("Today", "/owner/reports?from=2026-01-01&to=2026-01-01&status=delivered", true),
				tuple("Last 7 days", "/owner/reports?from=2025-12-26&to=2026-01-01&status=delivered", false),
				tuple("Last 30 days", "/owner/reports?status=delivered", false),
				tuple("This month", "/owner/reports?from=2026-01-01&to=2026-01-01&status=delivered", true),
				tuple("Last month", "/owner/reports?from=2025-12-01&to=2025-12-31&status=delivered", false));
	}

}

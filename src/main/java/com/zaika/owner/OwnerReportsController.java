package com.zaika.owner;

import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.catalog.CatalogRequests;
import com.zaika.common.Formatters;
import com.zaika.common.NotFoundException;
import com.zaika.owner.OwnerReportsService.Export;
import com.zaika.owner.OwnerReportsService.Report;
import com.zaika.security.CurrentUser;

/**
 * The owner's order history and reports ({@code /owner/reports}): a plain GET form (date range, status) over a revenue
 * summary, the top five dishes and a paginated orders table, plus the same orders as a CSV download
 * ({@code /owner/reports/orders.csv}). Non-canonical URLs (default values spelled out, junk, a page past the end)
 * redirect to the canonical one.
 */
@Controller
class OwnerReportsController {

	static final String VIEW = "owner/reports";

	/** Byte order mark, so spreadsheet apps read the file as UTF-8 (₹, names in Marathi). */
	private static final String BOM = "﻿";

	private final OwnerReportsService reports;

	private final Formatters fmt;

	OwnerReportsController(OwnerReportsService reports, Formatters fmt) {
		this.reports = reports;
		this.fmt = fmt;
	}

	@GetMapping("/owner/reports")
	String reports(@ModelAttribute("filter") ReportFilter filter, BindingResult unparseable,
			@AuthenticationPrincipal CurrentUser me, HttpServletRequest request, Model model,
			RedirectAttributes redirect) {
		// Values that do not parse (from=yesterday, page=two) were left empty; the redirect below drops them.
		Report report = reports.report(me.getId(), filter).orElse(null);
		if (report != null) {
			int last = Math.max(report.orders().getTotalPages(), 1);
			if (filter.getPage() > last) {
				filter.setPage(last);
			}
		}
		String canonical = filter.url();
		if (!canonical.equals(CatalogRequests.currentUrl(request))) {
			if (report != null && report.cut()) {
				redirect.addFlashAttribute("flashInfo", "Reports cover up to a year at a time, so this one starts on "
						+ fmt.date(filter.getFrom().atStartOfDay()) + ".");
			}
			return "redirect:" + canonical;
		}
		model.addAttribute("report", report);
		model.addAttribute("statuses", ReportFilter.STATUSES);
		return VIEW;
	}

	@GetMapping("/owner/reports/orders.csv")
	ResponseEntity<byte[]> csv(@ModelAttribute ReportFilter filter, BindingResult unparseable,
			@AuthenticationPrincipal CurrentUser me) {
		Export export = reports.export(me.getId(), filter)
			.orElseThrow(() -> new NotFoundException("No restaurant for owner " + me.getId()));
		return ResponseEntity.ok()
			.contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(export.filename()).build().toString())
			.body((BOM + export.content()).getBytes(StandardCharsets.UTF_8));
	}

}

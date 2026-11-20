package com.grocery.delivery.dispatch.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.delivery.dispatch.dto.DeliveryExports;
import com.grocery.delivery.dispatch.dto.DispatchDtos.AvailabilityRequest;
import com.grocery.delivery.dispatch.dto.DispatchDtos.DeliverySummary;
import com.grocery.delivery.dispatch.dto.DispatchDtos.DeliveryView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.FailRequest;
import com.grocery.delivery.dispatch.dto.DispatchDtos.HistorySummary;
import com.grocery.delivery.dispatch.dto.DispatchDtos.LocationRequest;
import com.grocery.delivery.dispatch.dto.DispatchDtos.OfferView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.PartnerStatusView;
import com.grocery.delivery.dispatch.entity.FailureReason;
import com.grocery.delivery.dispatch.service.AvailabilityService;
import com.grocery.delivery.dispatch.service.OfferService;
import com.grocery.delivery.dispatch.service.PartnerDeliveryService;
import com.grocery.delivery.stats.PartnerStatsService;
import com.grocery.delivery.stats.StatsDtos.PartnerStatsView;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The delivery partner's app (contract §3). The partner's id is the JWT subject (partnerId = userId), never taken
 * from the URL. A partner who is not APPROVED gets 403 PARTNER_NOT_ACTIVE on everything here.
 */
@RestController
@RequestMapping("/api/delivery")
@PreAuthorize("hasRole('DELIVERY_PARTNER')")
public class PartnerDispatchController {

    private final AvailabilityService availability;
    private final OfferService offers;
    private final PartnerDeliveryService deliveries;
    private final PartnerStatsService stats;
    private final Exporter exporter;

    public PartnerDispatchController(AvailabilityService availability, OfferService offers,
                                     PartnerDeliveryService deliveries, PartnerStatsService stats,
                                     Exporter exporter) {
        this.availability = availability;
        this.offers = offers;
        this.deliveries = deliveries;
        this.stats = stats;
        this.exporter = exporter;
    }

    @GetMapping("/partners/me/status")
    public PartnerStatusView status(CurrentUser user) {
        return availability.status(user.userId());
    }

    @PutMapping("/partners/me/availability")
    public PartnerStatusView availability(CurrentUser user, @Valid @RequestBody AvailabilityRequest request) {
        return availability.setOnline(user.userId(), request.online());
    }

    @PutMapping("/partners/me/location")
    public ResponseEntity<Void> location(CurrentUser user, @Valid @RequestBody LocationRequest request) {
        availability.reportLocation(user.userId(), request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/offers")
    public List<OfferView> offers(CurrentUser user) {
        return offers.myOffers(user.userId());
    }

    @PostMapping("/offers/{offerId}/accept")
    public DeliveryView accept(CurrentUser user, @PathVariable UUID offerId) {
        return offers.accept(user.userId(), offerId);
    }

    @PostMapping("/offers/{offerId}/reject")
    public ResponseEntity<Void> reject(CurrentUser user, @PathVariable UUID offerId) {
        offers.reject(user.userId(), offerId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/deliveries/active")
    public ResponseEntity<DeliveryView> active(CurrentUser user) {
        return deliveries.active(user.userId()).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/deliveries/{id}")
    public DeliveryView delivery(CurrentUser user, @PathVariable UUID id) {
        return deliveries.get(user.userId(), id);
    }

    @PostMapping("/deliveries/{id}/pickup")
    public DeliveryView pickUp(CurrentUser user, @PathVariable UUID id) {
        return deliveries.pickUp(user.userId(), id);
    }

    @PostMapping("/deliveries/{id}/deliver")
    public DeliveryView deliver(CurrentUser user, @PathVariable UUID id) {
        return deliveries.deliver(user.userId(), id);
    }

    @PostMapping("/deliveries/{id}/fail")
    public DeliveryView fail(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody FailRequest request) {
        return deliveries.fail(user.userId(), id, FailureReason.valueOf(request.reason()), request.note());
    }

    /**
     * The caller's finished deliveries, newest first. Optional filters: {@code status} DELIVERED | FAILED (absent =
     * both) and the India-time days {@code from}–{@code to} the delivery ended on (both included). An unknown status
     * or a malformed date is 400 INVALID_PARAMETER; {@code from} after {@code to} is 400 VALIDATION_FAILED.
     */
    @GetMapping("/history")
    public PageResponse<DeliverySummary> history(CurrentUser user, @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size) {
        return deliveries.history(user.userId(), status, from, to, page, size);
    }

    /**
     * The caller's delivery history as a file ({@code format} = csv | xlsx | pdf), newest first, with the list's
     * filters.
     */
    @GetMapping("/history/export")
    public void historyExport(CurrentUser user, @RequestParam(required = false) String format,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                              HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, DeliveryExports.PARTNER_HISTORY,
                deliveries.historyExport(user.userId(), status, from, to),
                Exporter.filters("Status", status, "From", from, "To", to));
    }

    @GetMapping("/history/summary")
    public HistorySummary summary(CurrentUser user) {
        return deliveries.summary(user.userId());
    }

    /**
     * The partner's own statistics for their dashboard's charts, over India-time days {@code from}–{@code to} (both
     * included, at most 92 days; without them the last 7 days). A malformed date is 400 INVALID_PARAMETER.
     */
    @GetMapping("/stats")
    public PartnerStatsView stats(CurrentUser user,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return stats.stats(user.userId(), from, to);
    }
}

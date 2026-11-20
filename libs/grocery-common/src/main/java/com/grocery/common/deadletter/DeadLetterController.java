package com.grocery.common.deadletter;

import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * {@code /api/admin/dead-letters} of every consuming service (contract Phase 10 §1), for this service's own consumer
 * groups only. Reads: any admin role; replay / discard / replay-all: SUPER_ADMIN, OPS_ADMIN (checked here, see
 * {@link DeadLetterAccess}). Registered by grocery-common's auto-configuration, not by component scanning.
 *
 * <ul>
 *   <li>{@code status} takes several values, repeated ({@code ?status=PENDING&status=REPLAY_FAILED}) or
 *       comma-separated ({@code ?status=PENDING,REPLAY_FAILED}); none = every status;</li>
 *   <li>the id ({@code <dltTopic>:<partition>:<offset>}) may arrive raw or URL-encoded
 *       ({@code vendor.events.DLT%3A0%3A12}); its dots are never taken as a file extension.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/dead-letters")
public class DeadLetterController {

    private final DeadLetterService service;

    public DeadLetterController(DeadLetterService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<DeadLetterView> list(@RequestParam(name = "status", required = false) List<String> status,
                                             @RequestParam(name = "consumerGroup", required = false) String consumerGroup,
                                             @RequestParam(name = "eventType", required = false) String eventType,
                                             @RequestParam(name = "page", required = false) Integer page,
                                             @RequestParam(name = "size", required = false) Integer size) {
        DeadLetterAccess.requireAdmin();
        return service.list(statuses(status), blankToNull(consumerGroup), blankToNull(eventType), page, size);
    }

    @GetMapping("/{id:.+}")
    public DeadLetterDetail get(@PathVariable("id") String id) {
        DeadLetterAccess.requireAdmin();
        return service.get(decoded(id));
    }

    @PostMapping("/{id:.+}/replay")
    public DeadLetterView replay(@PathVariable("id") String id) {
        String actor = DeadLetterAccess.requireOperator();
        return service.replay(decoded(id), actor);
    }

    @PostMapping("/{id:.+}/discard")
    public DeadLetterView discard(@PathVariable("id") String id,
                                  @RequestBody(required = false) DeadLetterRequests.Discard request) {
        String actor = DeadLetterAccess.requireOperator();
        return service.discard(decoded(id), request == null ? null : request.reason(), actor);
    }

    /** Body optional: {@code {}}, {@code {"consumerGroup"}}, {@code {"eventType"}} or both. */
    @PostMapping("/replay-all")
    public DeadLetterRequests.ReplayAllResult replayAll(@RequestBody(required = false) DeadLetterRequests.ReplayAll request) {
        String actor = DeadLetterAccess.requireOperator();
        return service.replayAll(request == null ? null : request.consumerGroup(),
                request == null ? null : request.eventType(), actor);
    }

    /** Repeated and/or comma-separated values; unknown → 400 INVALID_PARAMETER; none → null (every status). */
    static Set<DeadLetterStatus> statuses(List<String> values) {
        if (values == null) {
            return null;
        }
        Set<DeadLetterStatus> statuses = EnumSet.noneOf(DeadLetterStatus.class);
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (String part : value.split(",")) {
                DeadLetterStatus status = BadRequestException.parseEnum(DeadLetterStatus.class, "status", part);
                if (status != null) {
                    statuses.add(status);
                }
            }
        }
        return statuses.isEmpty() ? null : statuses;
    }

    /** Servlet containers decode the path; an id that was encoded twice (%253A) still resolves. Topics never hold '%'. */
    private static String decoded(String id) {
        return id != null && id.indexOf('%') >= 0 ? UriUtils.decode(id, StandardCharsets.UTF_8) : id;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

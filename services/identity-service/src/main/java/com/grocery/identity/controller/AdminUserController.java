package com.grocery.identity.controller;

import com.grocery.common.export.ExportFormat;
import com.grocery.common.export.Exporter;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.identity.dto.AuthDtos.AdminUserView;
import com.grocery.identity.dto.AuthDtos.CreateAdminRequest;
import com.grocery.identity.dto.AuthDtos.SuspendRequest;
import com.grocery.identity.dto.UserExports;
import com.grocery.identity.service.ClientInfo;
import com.grocery.identity.service.UserAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserAdminService service;
    private final Exporter exporter;

    public AdminUserController(UserAdminService service, Exporter exporter) {
        this.service = service;
        this.exporter = exporter;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT')")
    public PageResponse<AdminUserView> search(@RequestParam(required = false) String query,
                                              @RequestParam(required = false) String role,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        return service.search(query, role, status, page, size);
    }

    /**
     * The user list as a file ({@code format} = csv | xlsx | pdf): the same filters, every page, newest first or by
     * {@code sort} ({@code user} | {@code status} | {@code created}, {@code ,asc} | {@code ,desc}). The same roles as
     * the list; e-mail and phone are personal columns (kept for exactly these roles).
     */
    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN','SUPPORT')")
    public void export(CurrentUser user, @RequestParam(required = false) String format,
                       @RequestParam(required = false) String query,
                       @RequestParam(required = false) String role,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String sort, HttpServletResponse response) {
        ExportFormat fileFormat = ExportFormat.parse(format);
        exporter.write(response, user, fileFormat, UserExports.USERS.visibleTo(user),
                service.searchExport(query, role, status, sort),
                Exporter.filters("Search", query, "Role", role, "Status", status));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public AdminUserView createAdmin(@Valid @RequestBody CreateAdminRequest request, CurrentUser actor,
                                     HttpServletRequest http) {
        return service.createAdmin(request, actor, ClientInfo.from(http));
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN')")
    public AdminUserView suspend(@PathVariable UUID id, @Valid @RequestBody SuspendRequest request,
                                 CurrentUser actor, HttpServletRequest http) {
        return service.suspend(id, request.reason(), actor, ClientInfo.from(http));
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPS_ADMIN')")
    public AdminUserView reactivate(@PathVariable UUID id, CurrentUser actor, HttpServletRequest http) {
        return service.reactivate(id, actor, ClientInfo.from(http));
    }
}

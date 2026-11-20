package com.grocery.catalog.controller;

import com.grocery.catalog.dto.CategoryDtos.AdminCategoryNode;
import com.grocery.catalog.dto.CategoryDtos.CategoryRequest;
import com.grocery.catalog.service.CategoryAdminService;
import com.grocery.common.security.Roles;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Category tree management: any admin role may look; only SUPER_ADMIN / OPS_ADMIN may change it. */
@RestController
@RequestMapping("/api/admin/categories")
@PreAuthorize(Roles.ANY_ADMIN)
public class AdminCategoryController {

    private final CategoryAdminService categories;

    public AdminCategoryController(CategoryAdminService categories) {
        this.categories = categories;
    }

    @GetMapping
    public List<AdminCategoryNode> tree() {
        return categories.tree();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public AdminCategoryNode create(@Valid @RequestBody CategoryRequest request) {
        return categories.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public AdminCategoryNode update(@PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {
        return categories.update(id, request);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public AdminCategoryNode deactivate(@PathVariable UUID id) {
        return categories.deactivate(id);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public AdminCategoryNode activate(@PathVariable UUID id) {
        return categories.activate(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public void delete(@PathVariable UUID id) {
        categories.delete(id);
    }
}

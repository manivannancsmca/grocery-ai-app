package com.grocery.customer.dto;

import com.grocery.common.export.ExportSort;
import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportTable;
import com.grocery.customer.dto.CustomerDtos.CustomerProfile;
import com.grocery.customer.entity.Customer;

/** The CSV / Excel / PDF export of the admin customer directory. */
public final class CustomerExports {

    private CustomerExports() {
    }

    /**
     * Newest first like the list, or by name, join date or last profile change (the page's column keys; JPQL paths,
     * alias {@code c}). The phone may be empty, so it is not a sort option.
     */
    public static final ExportSort<Customer> SORT = ExportSort.<Customer>by(
                    Key.desc("c.createdAt", Customer::getCreatedAt), Key.desc("c.id", Customer::getId))
            .option("name", "c.fullName", Customer::getFullName)
            .option("joined", "c.createdAt", Customer::getCreatedAt)
            .option("updated", "c.updatedAt", Customer::getUpdatedAt)
            .build();

    /**
     * {@code GET /api/admin/customers/export}. E-mail and phone are personal: only support and operations admins get
     * them ({@link ExportTable#visibleTo}). Addresses are not in the list, so never in the export.
     */
    public static final ExportTable<CustomerProfile> CUSTOMERS = ExportTable.<CustomerProfile>named(
                    "customers", "Customers")
            .text("Name", CustomerProfile::fullName)
            .text("Email", CustomerProfile::email).personal()
            .text("Phone", CustomerProfile::phone).personal()
            .text("Customer ID", CustomerProfile::id)
            .dateTime("Joined", CustomerProfile::createdAt)
            .dateTime("Profile updated", CustomerProfile::updatedAt)
            .build();
}

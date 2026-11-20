package com.grocery.customer;

import com.grocery.common.id.Ids;
import com.grocery.customer.service.CustomerProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AddressBookIntegrationTest extends AbstractIntegrationTest {

    private static final String ADDRESSES = "/api/customers/me/addresses";

    @Autowired
    CustomerProfileService profiles;

    private static String address(String label, Boolean isDefault) {
        return """
                {"label":"%s","recipientName":"Asha Rao","phone":"+919876543210","line1":"12 MG Road","line2":null,
                 "landmark":"Near metro","city":"Bengaluru","state":"Karnataka","postalCode":"560001",
                 "latitude":12.9716,"longitude":77.5946,"isDefault":%s}""".formatted(label, isDefault);
    }

    private ResultActions add(UUID customer, String body) throws Exception {
        return mvc.perform(post(ADDRESSES).with(as(customer, "CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Adds an address and returns its id. */
    private String addAddress(UUID customer, String label, Boolean isDefault) throws Exception {
        String body = add(customer, address(label, isDefault)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asString();
    }

    private ResultActions book(UUID customer) throws Exception {
        return mvc.perform(get(ADDRESSES).with(as(customer, "CUSTOMER"))).andExpect(status().isOk());
    }

    private UUID registeredCustomer() {
        UUID customer = UUID.randomUUID();
        profiles.onCustomerUserRegistered(Ids.newId().toString(), customer, "Asha Rao", "asha@example.com", null);
        return customer;
    }

    @Test
    void customerManagesAnAddress() throws Exception {
        UUID customer = registeredCustomer();
        String created = add(customer, address("Home", false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.label").value("Home"))
                .andExpect(jsonPath("$.recipientName").value("Asha Rao"))
                .andExpect(jsonPath("$.phone").value("+919876543210"))
                .andExpect(jsonPath("$.line1").value("12 MG Road"))
                .andExpect(jsonPath("$.line2").isEmpty())
                .andExpect(jsonPath("$.landmark").value("Near metro"))
                .andExpect(jsonPath("$.city").value("Bengaluru"))
                .andExpect(jsonPath("$.state").value("Karnataka"))
                .andExpect(jsonPath("$.postalCode").value("560001"))
                .andExpect(jsonPath("$.country").value("IN"))
                .andExpect(jsonPath("$.latitude").value(12.9716))
                .andExpect(jsonPath("$.longitude").value(77.5946))
                .andExpect(jsonPath("$.isDefault").value(true)) // the first address is always default
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).get("id").asString();

        book(customer).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(id));
        // The single-address read order-service uses at checkout
        mvc.perform(get(ADDRESSES + "/{id}", id).with(as(customer, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.postalCode").value("560001"));

        mvc.perform(put(ADDRESSES + "/{id}", id).with(as(customer, "CUSTOMER")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Office","recipientName":"Asha R","phone":"9876500000","line1":"5 Marine Drive",
                                 "line2":"Floor 3","city":"Mumbai","state":"Maharashtra","postalCode":"400001",
                                 "country":"IN","isDefault":false}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.label").value("Office"))
                .andExpect(jsonPath("$.line2").value("Floor 3"))
                .andExpect(jsonPath("$.landmark").isEmpty())
                .andExpect(jsonPath("$.city").value("Mumbai"))
                .andExpect(jsonPath("$.latitude").isEmpty())
                .andExpect(jsonPath("$.isDefault").value(true)); // the only address cannot stop being default

        mvc.perform(delete(ADDRESSES + "/{id}", id).with(as(customer, "CUSTOMER")))
                .andExpect(status().isNoContent());
        mvc.perform(get(ADDRESSES + "/{id}", id).with(as(customer, "CUSTOMER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ADDRESS_NOT_FOUND"));
        book(customer).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void exactlyOneDefaultAndTheBookIsListedDefaultFirstThenNewest() throws Exception {
        UUID customer = registeredCustomer();
        String a = addAddress(customer, "A", null);
        String b = addAddress(customer, "B", false);
        book(customer)
                .andExpect(jsonPath("$[*].label", contains("A", "B")))
                .andExpect(jsonPath("$[?(@.isDefault == true)].label", contains("A")));

        add(customer, address("C", true)).andExpect(status().isCreated()).andExpect(jsonPath("$.isDefault").value(true));
        book(customer)
                .andExpect(jsonPath("$[*].label", contains("C", "B", "A")))
                .andExpect(jsonPath("$[?(@.isDefault == true)].label", contains("C")));

        mvc.perform(post(ADDRESSES + "/{id}/default", a).with(as(customer, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(a))
                .andExpect(jsonPath("$.isDefault").value(true));
        book(customer)
                .andExpect(jsonPath("$[*].label", contains("A", "C", "B")))
                .andExpect(jsonPath("$[?(@.isDefault == true)].label", contains("A")));

        // Making an address default through an update works too
        mvc.perform(put(ADDRESSES + "/{id}", b).with(as(customer, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content(address("B", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));
        book(customer)
                .andExpect(jsonPath("$[*].label", contains("B", "C", "A")))
                .andExpect(jsonPath("$[?(@.isDefault == true)].label", contains("B")));

        // isDefault=false never leaves the book without a default; making the default default again is a no-op
        mvc.perform(put(ADDRESSES + "/{id}", b).with(as(customer, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content(address("B2", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));
        mvc.perform(post(ADDRESSES + "/{id}/default", b).with(as(customer, "CUSTOMER")))
                .andExpect(status().isOk());
        book(customer).andExpect(jsonPath("$[?(@.isDefault == true)].label", contains("B2")));
    }

    @Test
    void deletingTheDefaultPromotesTheNewestRemainingAddress() throws Exception {
        UUID customer = registeredCustomer();
        String a = addAddress(customer, "A", null);
        String b = addAddress(customer, "B", null);
        String c = addAddress(customer, "C", null);
        mvc.perform(post(ADDRESSES + "/{id}/default", b).with(as(customer, "CUSTOMER"))).andExpect(status().isOk());

        mvc.perform(delete(ADDRESSES + "/{id}", b).with(as(customer, "CUSTOMER"))).andExpect(status().isNoContent());
        book(customer)
                .andExpect(jsonPath("$[*].label", contains("C", "A")))
                .andExpect(jsonPath("$[?(@.isDefault == true)].label", contains("C")));

        // Deleting a non-default address leaves the default alone
        mvc.perform(delete(ADDRESSES + "/{id}", a).with(as(customer, "CUSTOMER"))).andExpect(status().isNoContent());
        book(customer).andExpect(jsonPath("$[?(@.isDefault == true)].label", contains("C")));

        mvc.perform(delete(ADDRESSES + "/{id}", c).with(as(customer, "CUSTOMER"))).andExpect(status().isNoContent());
        book(customer).andExpect(jsonPath("$.length()").value(0));
        add(customer, address("D", false)).andExpect(jsonPath("$.isDefault").value(true));
    }

    @Test
    void aCustomerCanSaveAtMostTenAddresses() throws Exception {
        UUID customer = registeredCustomer();
        String first = null;
        for (int i = 1; i <= 10; i++) {
            String id = addAddress(customer, "Place " + i, null);
            first = first == null ? id : first;
        }
        add(customer, address("Place 11", null))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("ADDRESS_LIMIT_REACHED"));
        book(customer).andExpect(jsonPath("$.length()").value(10));

        mvc.perform(delete(ADDRESSES + "/{id}", first).with(as(customer, "CUSTOMER"))).andExpect(status().isNoContent());
        add(customer, address("Place 11", null)).andExpect(status().isCreated());
    }

    @Test
    void anotherCustomersAddressIsNotFound() throws Exception {
        UUID owner = registeredCustomer();
        String ownerAddress = addAddress(owner, "Owner home", null);
        UUID other = registeredCustomer();
        addAddress(other, "Other home", null);
        UUID stranger = UUID.randomUUID(); // no profile at all

        for (UUID intruder : new UUID[]{other, stranger}) {
            mvc.perform(get(ADDRESSES + "/{id}", ownerAddress).with(as(intruder, "CUSTOMER")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("ADDRESS_NOT_FOUND"));
            mvc.perform(put(ADDRESSES + "/{id}", ownerAddress).with(as(intruder, "CUSTOMER"))
                            .contentType(MediaType.APPLICATION_JSON).content(address("Hijacked", true)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("ADDRESS_NOT_FOUND"));
            mvc.perform(delete(ADDRESSES + "/{id}", ownerAddress).with(as(intruder, "CUSTOMER")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("ADDRESS_NOT_FOUND"));
            mvc.perform(post(ADDRESSES + "/{id}/default", ownerAddress).with(as(intruder, "CUSTOMER")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("ADDRESS_NOT_FOUND"));
        }
        book(other).andExpect(jsonPath("$[*].label", contains("Other home")));
        book(stranger).andExpect(jsonPath("$.length()").value(0));

        // The owner's address is untouched; an unknown id is not found either
        mvc.perform(get(ADDRESSES + "/{id}", ownerAddress).with(as(owner, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Owner home"))
                .andExpect(jsonPath("$.isDefault").value(true));
        mvc.perform(get(ADDRESSES + "/{id}", UUID.randomUUID()).with(as(owner, "CUSTOMER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidAddressesAreRejectedWithFieldErrors() throws Exception {
        UUID customer = registeredCustomer();
        add(customer, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(7))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder(
                        "label", "recipientName", "phone", "line1", "city", "state", "postalCode")));

        add(customer, """
                {"label":"Home","recipientName":"Asha Rao","phone":"98765","line1":"12 MG Road","city":"Bengaluru",
                 "state":"Karnataka","postalCode":"056001","country":"India","latitude":90.5,"longitude":-180.1}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(5))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder(
                        "phone", "postalCode", "country", "latitude", "longitude")));

        String id = addAddress(customer, "Home", null);
        mvc.perform(put(ADDRESSES + "/{id}", id).with(as(customer, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content(address("", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains("label")));
        book(customer).andExpect(jsonPath("$[*].label", contains("Home")));
    }

    @Test
    void addingTheFirstAddressCreatesTheProfileOnDemand() throws Exception {
        UUID customer = UUID.randomUUID();
        mvc.perform(get("/api/customers/me").with(as(customer, "CUSTOMER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_PROFILE_NOT_FOUND"));

        add(customer, address("Home", null)).andExpect(status().isCreated()).andExpect(jsonPath("$.isDefault").value(true));
        mvc.perform(get("/api/customers/me").with(as(customer, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customer.toString()))
                .andExpect(jsonPath("$.email").value(emailOf(customer)))
                .andExpect(jsonPath("$.fullName").value(nameOf(customer)));

        // The late registration event changes nothing
        profiles.onCustomerUserRegistered(Ids.newId().toString(), customer, "Late Name", "late@example.com", null);
        mvc.perform(get("/api/customers/me").with(as(customer, "CUSTOMER")))
                .andExpect(jsonPath("$.fullName").value(nameOf(customer)));
        book(customer).andExpect(jsonPath("$[*].label", contains("Home")));
    }
}

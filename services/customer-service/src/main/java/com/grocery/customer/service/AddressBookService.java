package com.grocery.customer.service;

import com.grocery.common.id.Ids;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.BusinessRuleException;
import com.grocery.common.web.NotFoundException;
import com.grocery.customer.dto.CustomerDtos.AddressRequest;
import com.grocery.customer.dto.CustomerDtos.AddressView;
import com.grocery.customer.entity.Customer;
import com.grocery.customer.entity.CustomerAddress;
import com.grocery.customer.mapper.CustomerMapper;
import com.grocery.customer.repository.CustomerAddressRepository;
import com.grocery.customer.repository.CustomerRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The customer's delivery addresses. Book-wide rules: at most {@value #MAX_ADDRESSES} addresses, and
 * exactly one default while any exist (the first is always default; deleting the default promotes the
 * newest remaining one). Every change holds the customer row lock, so concurrent requests cannot break
 * either rule. Another customer's address is always reported as not found, never as forbidden.
 */
@Service
public class AddressBookService {

    public static final int MAX_ADDRESSES = 10;

    /** Default first, then newest; UUIDv7 ids keep creation order when timestamps tie. */
    private static final Sort BOOK_ORDER = Sort.by(Sort.Order.desc("defaultAddress"), Sort.Order.desc("createdAt"),
            Sort.Order.desc("id"));
    private static final Comparator<CustomerAddress> CREATION_ORDER =
            Comparator.comparing(CustomerAddress::getCreatedAt).thenComparing(CustomerAddress::getId);

    private final CustomerAddressRepository addresses;
    private final CustomerRepository customers;
    private final CustomerProfileService profiles;
    private final Clock clock;

    public AddressBookService(CustomerAddressRepository addresses, CustomerRepository customers,
                              CustomerProfileService profiles, Clock clock) {
        this.addresses = addresses;
        this.customers = customers;
        this.profiles = profiles;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AddressView> list(CurrentUser user) {
        return addresses.findByCustomerId(user.userId(), BOOK_ORDER).stream().map(CustomerMapper::toView).toList();
    }

    /** Also used by order-service at checkout (with the customer's own token). */
    @Transactional(readOnly = true)
    public AddressView get(CurrentUser user, UUID addressId) {
        return CustomerMapper.toView(addresses.findByIdAndCustomerId(addressId, user.userId())
                .orElseThrow(AddressBookService::notFound));
    }

    /** Creates the profile on demand if the registration event has not arrived yet. */
    @Transactional
    public AddressView add(CurrentUser user, AddressRequest request) {
        Customer customer = profiles.lockOrCreate(user);
        List<CustomerAddress> book = addresses.findByCustomerId(customer.getId(), BOOK_ORDER);
        if (book.size() >= MAX_ADDRESSES) {
            throw new BusinessRuleException("ADDRESS_LIMIT_REACHED",
                    "You can save at most " + MAX_ADDRESSES + " addresses. Delete one to add another.");
        }
        Instant now = clock.instant();
        CustomerAddress address = CustomerAddress.create(Ids.newId(), customer.getId(), now);
        apply(address, request, now);
        if (book.isEmpty() || Boolean.TRUE.equals(request.isDefault())) {
            book.forEach(other -> other.setDefault(false, now));
            address.setDefault(true, now);
        }
        return CustomerMapper.toView(addresses.save(address));
    }

    /** Replaces the address. {@code isDefault: true} makes it the default; false never un-defaults it. */
    @Transactional
    public AddressView update(CurrentUser user, UUID addressId, AddressRequest request) {
        List<CustomerAddress> book = lockBook(user);
        CustomerAddress address = find(book, addressId);
        Instant now = clock.instant();
        apply(address, request, now);
        if (Boolean.TRUE.equals(request.isDefault())) {
            makeDefault(book, address, now);
        }
        return CustomerMapper.toView(address);
    }

    @Transactional
    public AddressView makeDefault(CurrentUser user, UUID addressId) {
        List<CustomerAddress> book = lockBook(user);
        CustomerAddress address = find(book, addressId);
        makeDefault(book, address, clock.instant());
        return CustomerMapper.toView(address);
    }

    @Transactional
    public void delete(CurrentUser user, UUID addressId) {
        List<CustomerAddress> book = lockBook(user);
        CustomerAddress address = find(book, addressId);
        addresses.delete(address);
        if (address.isDefaultAddress()) {
            Instant now = clock.instant();
            book.stream().filter(other -> other != address).max(CREATION_ORDER)
                    .ifPresent(newest -> newest.setDefault(true, now));
        }
    }

    /** Locks the caller's profile row, then loads the whole book (at most 10 rows). No profile → no addresses. */
    private List<CustomerAddress> lockBook(CurrentUser user) {
        customers.findByIdForUpdate(user.userId()).orElseThrow(AddressBookService::notFound);
        return addresses.findByCustomerId(user.userId(), BOOK_ORDER);
    }

    private static CustomerAddress find(List<CustomerAddress> book, UUID addressId) {
        return book.stream().filter(a -> a.getId().equals(addressId)).findFirst()
                .orElseThrow(AddressBookService::notFound);
    }

    /** Setting a default un-defaults every other address (same transaction). */
    private static void makeDefault(List<CustomerAddress> book, CustomerAddress target, Instant now) {
        book.forEach(a -> a.setDefault(a == target, now));
    }

    private static void apply(CustomerAddress address, AddressRequest r, Instant now) {
        address.updateDetails(CustomerMapper.trim(r.label()), CustomerMapper.trim(r.recipientName()),
                CustomerMapper.trim(r.phone()), CustomerMapper.trim(r.line1()), CustomerMapper.trim(r.line2()),
                CustomerMapper.trim(r.landmark()), CustomerMapper.trim(r.city()), CustomerMapper.trim(r.state()),
                CustomerMapper.trim(r.postalCode()), r.country() == null ? "IN" : r.country(), r.latitude(),
                r.longitude(), now);
    }

    static NotFoundException notFound() {
        return new NotFoundException("ADDRESS_NOT_FOUND", "Address not found");
    }
}

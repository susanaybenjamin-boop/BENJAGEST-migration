package com.benjagest.backend.customer;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CustomerService {

    private final CustomerRepository repository;

    public CustomerService(CustomerRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public CustomerResponse create(CustomerCreateRequest request) {
        String customerId = UUID.randomUUID().toString();
        String contactId = UUID.randomUUID().toString();

        try {
            repository.insertCustomer(customerId, request);
            repository.insertPrimaryContact(contactId, customerId, request);
            return repository.findById(customerId);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un cliente con ese identificador fiscal");
        }
    }

    public List<CustomerResponse> list() {
        return repository.findAllActive();
    }
}

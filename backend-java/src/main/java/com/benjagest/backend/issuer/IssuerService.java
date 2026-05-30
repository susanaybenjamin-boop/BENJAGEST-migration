package com.benjagest.backend.issuer;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Capa de negocio para emisores. Lo que aporta sobre el Repository:
 *   - Generacion del id (UUID) en el momento de crear.
 *   - Conversion de errores de BD a errores HTTP que el cliente entiende
 *     (409 Conflict si ya existe un emisor con el mismo NIF en la empresa,
 *      404 Not Found si se pide editar/borrar un id inexistente).
 *   - @Transactional cuando un metodo hace mas de una operacion de BD.
 *
 * Idealmente el Controller no deberia tocar nunca el Repository
 * directamente — siempre debe pasar por aqui.
 */
@Service
public class IssuerService {

    private final IssuerRepository repository;

    public IssuerService(IssuerRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public IssuerResponse create(IssuerCreateRequest request) {
        String id = UUID.randomUUID().toString();
        try {
            repository.insert(id, request);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ya existe un emisor con ese identificador fiscal para esta empresa"
            );
        }
        return repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "El emisor no se pudo recuperar tras crearlo")
        );
    }

    @Transactional
    public IssuerResponse update(String id, IssuerCreateRequest request) {
        int affected;
        try {
            affected = repository.update(id, request);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ya existe otro emisor con ese identificador fiscal para esta empresa"
            );
        }
        if (affected == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Emisor no encontrado");
        }
        return repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Emisor no encontrado")
        );
    }

    @Transactional
    public void delete(String id) {
        int affected = repository.softDelete(id);
        if (affected == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Emisor no encontrado");
        }
    }

    public List<IssuerResponse> list() {
        return repository.findAllActive();
    }

    public IssuerResponse findById(String id) {
        return repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Emisor no encontrado")
        );
    }
}

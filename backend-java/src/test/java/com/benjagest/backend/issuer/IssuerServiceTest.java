package com.benjagest.backend.issuer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tests del Service. No necesita BD ni Spring arrancado: mockeamos el
 * Repository y comprobamos que el Service convierte cada caso a la
 * respuesta o excepcion HTTP correcta.
 */
class IssuerServiceTest {

    private IssuerRepository repository;
    private IssuerService service;

    @BeforeEach
    void setup() {
        repository = mock(IssuerRepository.class);
        service = new IssuerService(repository);
    }

    @Test
    void create_devuelve_emisor_cuando_insert_va_bien() {
        IssuerCreateRequest request = sampleRequest("B12345678");
        when(repository.findById(anyString())).thenAnswer(invocation ->
                Optional.of(sampleResponse(invocation.getArgument(0), "B12345678"))
        );

        IssuerResponse response = service.create(request);

        assertThat(response.taxIdentifier()).isEqualTo("B12345678");
        assertThat(response.id()).isNotBlank();
        verify(repository).insert(anyString(), any());
    }

    @Test
    void create_lanza_409_si_existe_otro_con_mismo_nif() {
        IssuerCreateRequest request = sampleRequest("B12345678");
        doThrowOnInsert(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void update_lanza_404_si_el_id_no_existe() {
        when(repository.update(anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.update("missing-id", sampleRequest("B12345678")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void update_devuelve_emisor_cuando_afecta_a_una_fila() {
        when(repository.update(anyString(), any())).thenReturn(1);
        when(repository.findById("known-id")).thenReturn(Optional.of(sampleResponse("known-id", "B99999999")));

        IssuerResponse response = service.update("known-id", sampleRequest("B99999999"));

        assertThat(response.id()).isEqualTo("known-id");
    }

    @Test
    void delete_lanza_404_si_el_id_no_existe() {
        when(repository.softDelete("missing-id")).thenReturn(0);

        assertThatThrownBy(() -> service.delete("missing-id"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void delete_ok_cuando_afecta_a_una_fila() {
        when(repository.softDelete("known-id")).thenReturn(1);

        service.delete("known-id");

        verify(repository).softDelete("known-id");
    }

    @Test
    void list_devuelve_lo_que_diga_el_repositorio() {
        when(repository.findAllActive()).thenReturn(java.util.List.of(sampleResponse("id-1", "B11111111")));

        assertThat(service.list()).hasSize(1);
    }

    private void doThrowOnInsert(RuntimeException exception) {
        org.mockito.Mockito.doThrow(exception).when(repository).insert(anyString(), any());
    }

    private IssuerCreateRequest sampleRequest(String tax) {
        return new IssuerCreateRequest(
                "Razon social SL", tax,
                "Calle Falsa 1", "Madrid", "Madrid", "28001", "Espana",
                "facturacion@demo.local", "910000000",
                "ES7620770024003102575766",
                null, null, null
        );
    }

    private IssuerResponse sampleResponse(String id, String tax) {
        return new IssuerResponse(
                id, "Razon social SL", tax,
                "Calle Falsa 1", "Madrid", "Madrid", "28001", "Espana",
                "facturacion@demo.local", "910000000",
                "ES7620770024003102575766",
                null, null, null,
                true, Instant.now(), Instant.now()
        );
    }
}

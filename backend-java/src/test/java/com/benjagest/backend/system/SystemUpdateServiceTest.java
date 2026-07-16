package com.benjagest.backend.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UPD-CLEAN — la retención de instaladores es lógica pura y fácil de equivocar
 * (orden de versiones, no borrar el que se instala). Se prueba aparte de la
 * descarga real.
 */
class SystemUpdateServiceTest {

    // --- versionFromUrl -----------------------------------------------------

    @Test
    void versionFromUrl_extraeLaVersionDelAsset() {
        assertEquals("0.1.43", SystemUpdateService.versionFromUrl(
                "https://github.com/x/y/releases/download/v0.1.43/BENJAGEST-0.1.43.msi"));
    }

    @Test
    void versionFromUrl_nombreSinVersion_null() {
        assertNull(SystemUpdateService.versionFromUrl(
                "https://x/BENJAGEST-update.msi"));
        assertNull(SystemUpdateService.versionFromUrl(null));
    }

    // --- compareVersions ----------------------------------------------------

    @Test
    void compareVersions_esNumericaNoAlfabetica() {
        // El caso que un sort de texto se come: 0.1.9 es ANTERIOR a 0.1.10.
        assertTrue(SystemUpdateService.compareVersions("0.1.9", "0.1.10") < 0);
        assertTrue(SystemUpdateService.compareVersions("0.1.10", "0.1.9") > 0);
        assertEquals(0, SystemUpdateService.compareVersions("0.1.43", "0.1.43"));
    }

    @Test
    void compareVersions_sinVersionEsLaMenor() {
        assertTrue(SystemUpdateService.compareVersions(null, "0.1.1") < 0);
        assertTrue(SystemUpdateService.compareVersions("0.1.1", null) > 0);
    }

    // --- pruneOldInstallers -------------------------------------------------

    private void touch(Path dir, String name) throws Exception {
        Files.writeString(dir.resolve(name), "x");
    }

    private List<String> msisIn(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".msi"))
                    .sorted()
                    .toList();
        }
    }

    @Test
    void prune_dejaLosTresMasNuevos_borraElResto(@TempDir Path dir) throws Exception {
        for (String v : List.of("0.1.40", "0.1.41", "0.1.42", "0.1.43")) {
            touch(dir, "BENJAGEST-" + v + ".msi");
        }
        Path justDownloaded = dir.resolve("BENJAGEST-0.1.43.msi");

        SystemUpdateService.pruneOldInstallers(dir, justDownloaded);

        // KEEP=3: se quedan 43 (el nuevo) + 42 + 41; se borra 40.
        assertEquals(List.of(
                "BENJAGEST-0.1.41.msi", "BENJAGEST-0.1.42.msi", "BENJAGEST-0.1.43.msi"),
                msisIn(dir));
    }

    @Test
    void prune_ordenNumerico_noBorraLaBuenaPorElSalto9a10(@TempDir Path dir) throws Exception {
        for (String v : List.of("0.1.8", "0.1.9", "0.1.10", "0.1.11")) {
            touch(dir, "BENJAGEST-" + v + ".msi");
        }
        SystemUpdateService.pruneOldInstallers(dir, dir.resolve("BENJAGEST-0.1.11.msi"));

        // Numérico: se quedan 11, 10, 9; se borra la 8. Un sort de texto habría
        // dejado "0.1.8"/"0.1.9" arriba y borrado "0.1.10"/"0.1.11".
        assertEquals(List.of(
                "BENJAGEST-0.1.10.msi", "BENJAGEST-0.1.11.msi", "BENJAGEST-0.1.9.msi"),
                msisIn(dir));
    }

    @Test
    void prune_nuncaBorraElReciénDescargado_aunqueSeaViejo(@TempDir Path dir) throws Exception {
        // Escenario raro pero real: se reinstala una versión ANTERIOR a mano.
        // El MSI que se está instalando no se puede borrar aunque no esté entre
        // los 3 más nuevos.
        for (String v : List.of("0.1.40", "0.1.50", "0.1.51", "0.1.52")) {
            touch(dir, "BENJAGEST-" + v + ".msi");
        }
        Path justDownloaded = dir.resolve("BENJAGEST-0.1.40.msi");

        SystemUpdateService.pruneOldInstallers(dir, justDownloaded);

        assertTrue(Files.exists(justDownloaded), "el que se instala se conserva siempre");
        // Con la 40 forzada dentro, KEEP se llena con 52 y 51; se borra la 50.
        assertFalse(Files.exists(dir.resolve("BENJAGEST-0.1.50.msi")));
        assertTrue(Files.exists(dir.resolve("BENJAGEST-0.1.52.msi")));
        assertTrue(Files.exists(dir.resolve("BENJAGEST-0.1.51.msi")));
    }

    @Test
    void prune_barreElUpdateMsiSinVersionDelFlujoViejo(@TempDir Path dir) throws Exception {
        touch(dir, "BENJAGEST-update.msi"); // basura del actualizador anterior
        for (String v : List.of("0.1.42", "0.1.43")) {
            touch(dir, "BENJAGEST-" + v + ".msi");
        }
        SystemUpdateService.pruneOldInstallers(dir, dir.resolve("BENJAGEST-0.1.43.msi"));

        // Sin versión = la menor -> fuera de los 3 nuevos -> se borra.
        assertFalse(Files.exists(dir.resolve("BENJAGEST-update.msi")));
        assertEquals(List.of("BENJAGEST-0.1.42.msi", "BENJAGEST-0.1.43.msi"), msisIn(dir));
    }
}

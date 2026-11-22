package com.benjagest.ui.service;

import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * UPD-TLS (2026-11-22) — contexto SSL que confía en el almacén de Java <b>y
 * además</b> en el de Windows, para el actualizador.
 *
 * <p><b>Gemela de {@code com.benjagest.backend.config.WindowsAwareTrust}.</b> Es
 * una copia a propósito, no un descuido: el módulo {@code ui} no depende de
 * {@code backend-java} (arrastraría Spring entero a la aplicación de
 * escritorio), así que no hay forma de reutilizar aquella clase sin inventar un
 * módulo común y tocar el {@code pom} raíz, los tres módulos y el empaquetado.
 * Es el mismo criterio que ya se tomó con {@code doneMarkerPath()} en
 * {@link UpdateService}. <b>Si se toca una, hay que tocar la otra</b>; el
 * contenido es idéntico salvo el paquete y que aquí no hay slf4j.
 *
 * <p><b>Por qué hace falta en el actualizador.</b> Era el <b>único</b> cliente
 * HTTP de toda la aplicación que seguía validando solo contra el {@code cacerts}
 * de Java. El caso que lo justifica no es que GitHub cambie de autoridad
 * certificadora — usa autoridades globales que sí están en {@code cacerts} —
 * sino el más común de todos: un <b>antivirus o un proxy de empresa que
 * inspecciona el tráfico TLS</b> y lo re-firma con una autoridad propia. Esa
 * autoridad se instala en el almacén de Windows y <b>nunca</b> en el de Java,
 * así que el navegador y el resto del sistema funcionan y solo la comprobación
 * de actualizaciones se cae.
 *
 * <p>Y cuando se cae, se cae en silencio: el actualizador diría "no se pudo
 * preguntar" indefinidamente mientras dejan de llegar los arreglos. Es la misma
 * avería silenciosa que tuvo el BOE <b>34 días</b> en octubre.
 *
 * <p><b>Por qué así y no con
 * {@code -Djavax.net.ssl.trustStoreType=WINDOWS-ROOT}</b>: esa propiedad
 * <b>sustituye</b> el almacén de TODA la JVM, así que cualquier sitio que ya
 * funcionaba pasaría a depender del almacén de Windows. Aquí se SUMAN los dos:
 * primero se valida con el de Java y, si rechaza, con el de Windows. Nada de lo
 * que funcionaba puede romperse por esto.
 *
 * <p>No se baja la seguridad: no hay {@code TrustManager} permisivo ni se acepta
 * nada sin validar. Si ninguno de los dos almacenes valida la cadena, la
 * conexión se rechaza igual que antes.
 */
final class WindowsAwareTrust {

    private WindowsAwareTrust() {
    }

    /**
     * Contexto SSL que valida contra el almacén por defecto y, si ese rechaza,
     * contra el de Windows. Si algo falla al construirlo devuelve {@code null}
     * para que el llamante use el contexto por defecto: preferimos el
     * comportamiento de siempre a quedarnos sin cliente HTTP.
     */
    static SSLContext sslContextOrNull() {
        try {
            TrustManager[] tms = trustManagersOrNull();
            if (tms == null) return null;
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tms, null);
            return ctx;
        } catch (Exception ex) {
            System.err.println("[WindowsAwareTrust] no se pudo montar el contexto con el "
                    + "almacen de Windows; se usara el de Java: " + ex);
            return null;
        }
    }

    /**
     * Los {@code TrustManager} sueltos, para quien ya monta su propio
     * {@link SSLContext} y solo necesita la parte de confianza. Devolver
     * {@code null} es seguro: {@code SSLContext.init} con trust managers nulos
     * usa el almacén por defecto de la JVM.
     */
    static TrustManager[] trustManagersOrNull() {
        try {
            List<X509TrustManager> delegates = new ArrayList<>();
            X509TrustManager def = load(null);
            if (def != null) delegates.add(def);
            X509TrustManager win = windows();
            if (win != null) delegates.add(win);
            if (delegates.isEmpty()) return null;
            return new TrustManager[] { new AnyOf(delegates) };
        } catch (Exception ex) {
            System.err.println("[WindowsAwareTrust] no se pudieron montar los trust managers: " + ex);
            return null;
        }
    }

    /** El almacén "Windows-ROOT" solo existe en Windows; fuera devuelve null. */
    private static X509TrustManager windows() {
        try {
            KeyStore ks = KeyStore.getInstance("Windows-ROOT");
            ks.load(null, null);
            return load(ks);
        } catch (Exception ex) {
            // En Windows no deberia pasar: el runtime del .msi incluye
            // jdk.crypto.mscapi (comprobado en runtime/release, 2026-11-22).
            return null;
        }
    }

    /** {@code null} = almacén por defecto de la JVM (cacerts). */
    private static X509TrustManager load(KeyStore ks) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager x) return x;
        }
        return null;
    }

    /**
     * Da por buena la cadena si la acepta CUALQUIERA de los almacenes. Si
     * ninguno la acepta, propaga la excepción del primero, que es la que
     * describe el caso normal ("no encuentro la raíz en cacerts").
     */
    private record AnyOf(List<X509TrustManager> delegates) implements X509TrustManager {

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            CertificateException first = null;
            for (X509TrustManager tm : delegates) {
                try {
                    tm.checkServerTrusted(chain, authType);
                    return;
                } catch (CertificateException ex) {
                    if (first == null) first = ex;
                }
            }
            throw first == null
                    ? new CertificateException("Sin almacenes de confianza") : first;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            CertificateException first = null;
            for (X509TrustManager tm : delegates) {
                try {
                    tm.checkClientTrusted(chain, authType);
                    return;
                } catch (CertificateException ex) {
                    if (first == null) first = ex;
                }
            }
            throw first == null
                    ? new CertificateException("Sin almacenes de confianza") : first;
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            Set<X509Certificate> all = new LinkedHashSet<>();
            for (X509TrustManager tm : delegates) {
                X509Certificate[] is = tm.getAcceptedIssuers();
                if (is != null) {
                    for (X509Certificate c : is) all.add(c);
                }
            }
            return all.toArray(new X509Certificate[0]);
        }
    }
}

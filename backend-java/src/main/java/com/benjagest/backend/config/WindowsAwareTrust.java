package com.benjagest.backend.config;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BOE-TLS (2026-11-20) — contexto SSL que confía en el almacén de Java
 * <b>y además</b> en el de Windows.
 *
 * <p><b>Por qué existe.</b> El 18 de octubre de 2026 el BOE renovó el
 * certificado de {@code www.boe.es} a una cadena emitida por la
 * <b>FNMT-RCM</b> (AC Componentes Informáticos → AC RAIZ FNMT-RCM). Esa raíz
 * está en el almacén de Windows, pero <b>no viene en el {@code cacerts} de
 * Java</b> — ni en el del JDK ni en el {@code runtime} que empaqueta el MSI.
 * Resultado: el barrido diario del BOE empezó a morir con
 * {@code PKIX path building failed} y, como el servicio captura la excepción y
 * sigue, <b>falló en silencio todos los días</b>.
 *
 * <p>Se vio en el log de producción de Benjamin: último barrido con éxito el
 * <b>17-oct-2026</b> ("6 hits"), y a partir del 18 <b>un WARN por día</b>
 * durante más de un mes. Él seguía viendo avisos viejos en la pantalla y no
 * tenía forma de saber que habían dejado de llegar.
 *
 * <p><b>Por qué así y no con {@code -Djavax.net.ssl.trustStoreType=WINDOWS-ROOT}</b>:
 * esa propiedad <b>sustituye</b> el almacén de TODA la JVM, así que cualquier
 * sitio que la app ya contactaba bien (AEAT, correo, Google) pasaría a depender
 * del almacén de Windows. Aquí se SUMAN los dos: primero se intenta validar con
 * el de Java y, si rechaza, con el de Windows. Nada de lo que funcionaba puede
 * romperse por esto.
 *
 * <p>No se baja la seguridad: no hay {@code TrustManager} permisivo ni se
 * acepta nada sin validar. Si ninguno de los dos almacenes valida la cadena,
 * la conexión se rechaza igual que antes.
 */
public final class WindowsAwareTrust {

    private static final Logger log = LoggerFactory.getLogger(WindowsAwareTrust.class);

    private WindowsAwareTrust() {
    }

    /**
     * Contexto SSL que valida contra el almacén por defecto y, si ese rechaza,
     * contra el de Windows. Si algo falla al construirlo devuelve {@code null}
     * para que el llamante use el contexto por defecto: preferimos el
     * comportamiento de siempre a quedarnos sin cliente HTTP.
     */
    public static SSLContext sslContextOrNull() {
        try {
            TrustManager[] tms = trustManagersOrNull();
            if (tms == null) return null;
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tms, null);
            return ctx;
        } catch (Exception ex) {
            log.warn("TLS: no se pudo montar el contexto con el almacen de Windows; "
                    + "se usara el de Java", ex);
            return null;
        }
    }

    /**
     * Los {@code TrustManager} sueltos, para quien ya monta su propio
     * {@link SSLContext} por otro motivo y solo necesita la parte de
     * confianza.
     *
     * <p>El caso es el cliente de AEAT: necesita sus propios
     * {@code KeyManager} (el certificado de la empresa, que es como AEAT la
     * identifica), asi que no puede usar un contexto ya montado. Devolver
     * {@code null} es seguro: {@code SSLContext.init} con trust managers nulos
     * usa el almacen por defecto de la JVM.
     */
    public static TrustManager[] trustManagersOrNull() {
        try {
            List<X509TrustManager> delegates = new ArrayList<>();
            X509TrustManager def = load(null);
            if (def != null) delegates.add(def);
            X509TrustManager win = windows();
            if (win != null) delegates.add(win);
            if (delegates.isEmpty()) return null;
            return new TrustManager[] { new AnyOf(delegates) };
        } catch (Exception ex) {
            log.warn("TLS: no se pudieron montar los trust managers", ex);
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
            log.debug("TLS: almacen de Windows no disponible ({})", ex.toString());
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

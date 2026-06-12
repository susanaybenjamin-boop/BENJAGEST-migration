package com.benjagest.backend.billing.tpb;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Endpoints PUBLICOS (sin autenticacion) para la firma electronica
 * Magic Link + OTP por parte del cliente que no tiene cuenta en
 * BENJAGEST.
 *
 * <p>El cliente recibe por email un enlace con un token unico. Al
 * abrirlo se le sirve una pagina HTML simple para leer el acuerdo,
 * descargar el PDF y firmar introduciendo el OTP recibido por email.
 * Tras firmar el acuerdo queda ACTIVE.
 *
 * <p>El acceso sin JWT se permite via SecurityConfig — la ruta
 * /api/public/tpb/** se anade a permitAll.
 */
@RestController
@RequestMapping("/api/public/tpb")
public class PublicTpbController {

    private final TpbMagicLinkService magicLinkService;
    private final ThirdPartyBillingAgreementService agreementService;

    public PublicTpbController(TpbMagicLinkService magicLinkService,
                                 ThirdPartyBillingAgreementService agreementService) {
        this.magicLinkService = magicLinkService;
        this.agreementService = agreementService;
    }

    /** Pagina HTML simple para que el cliente lea el acuerdo y firme. */
    @GetMapping(value = "/sign-page", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> signPage(@RequestParam("token") String token) {
        var t = magicLinkService.findActiveByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Enlace no encontrado"));
        String stateMsg;
        boolean canSign = true;
        if (t.usedAt() != null) {
            stateMsg = "Este enlace ya fue usado para firmar el acuerdo. Si crees que no fuiste tu, contacta urgentemente con tu asesoria.";
            canSign = false;
        } else if (t.invalidatedAt() != null) {
            stateMsg = "Enlace invalidado tras demasiados intentos fallidos. Solicita a tu asesoria un nuevo envio.";
            canSign = false;
        } else if (java.time.Instant.now().isAfter(t.expiresAt())) {
            stateMsg = "El enlace ha caducado. Solicita a tu asesoria un nuevo envio.";
            canSign = false;
        } else {
            stateMsg = "Lee el acuerdo y firma con el codigo OTP que recibiste por email.";
        }
        String html = renderPage(token, t.agreementId(), stateMsg, canSign);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html; charset=UTF-8"))
                .body(html);
    }

    /** Devuelve el PDF del acuerdo para que el cliente lo vea/descargue. */
    @GetMapping("/{token}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable("token") String token) {
        var t = magicLinkService.findActiveByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Enlace no encontrado"));
        byte[] pdf = agreementService.generateProposalPdf(t.agreementId());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"acuerdo-facturacion.pdf\"")
                .body(pdf);
    }

    /** El cliente envia el OTP que recibio por email. */
    @PostMapping("/{token}/sign")
    public Map<String, Object> sign(@PathVariable("token") String token,
                                      @RequestBody SignRequest body,
                                      HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
        String ua = request.getHeader("User-Agent");
        String agreementId = magicLinkService.signWithOtp(token, body.otp(), ip, ua);
        return Map.of("ok", true, "agreementId", agreementId);
    }

    public record SignRequest(String otp) {}

    private static String renderPage(String token, String agreementId,
                                       String stateMsg, boolean canSign) {
        String html = """
            <!DOCTYPE html>
            <html lang="es">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Firma de acuerdo de facturacion por tercero</title>
              <style>
                body { font-family: -apple-system, Segoe UI, Roboto, sans-serif;
                       max-width: 720px; margin: 24px auto; padding: 0 16px;
                       color: #1e293b; line-height: 1.5; }
                h1 { color: #0f172a; font-size: 22px; margin-bottom: 8px; }
                .sub { color: #64748b; font-size: 14px; margin-bottom: 24px; }
                .card { border: 1px solid #e2e8f0; border-radius: 12px;
                        padding: 20px; margin-bottom: 20px; background: #f8fafc; }
                .pdf { width: 100%; height: 460px; border: 1px solid #cbd5e1;
                       border-radius: 8px; }
                a.dl { display: inline-block; margin-top: 8px; color: #2563eb; }
                input { padding: 12px; font-size: 18px; width: 200px;
                        letter-spacing: 4px; border: 2px solid #cbd5e1;
                        border-radius: 8px; text-align: center; }
                button { padding: 12px 22px; background: #2563eb; color: #fff;
                         border: 0; border-radius: 8px; font-size: 16px;
                         cursor: pointer; margin-left: 8px; }
                button:disabled { background: #94a3b8; cursor: not-allowed; }
                .err { color: #b91c1c; margin-top: 12px; }
                .ok  { color: #15803d; margin-top: 12px; font-weight: bold; }
                .note { font-size: 12px; color: #475569; margin-top: 12px; }
              </style>
            </head>
            <body>
              <h1>Acuerdo de facturacion por tercero</h1>
              <p class="sub">RD 1619/2012 art. 5 - firma electronica simple (eIDAS art. 25)</p>

              <div class="card">
                <p><strong>%s</strong></p>
              </div>

              <div class="card">
                <h2 style="font-size:16px;margin:0 0 8px">Lee el acuerdo</h2>
                <iframe class="pdf" src="/api/public/tpb/%s/pdf"></iframe>
                <a class="dl" href="/api/public/tpb/%s/pdf" download>Descargar PDF</a>
              </div>

              %s

              <p class="note">Quedan registrados como evidencia legal su IP, navegador, hora del click y del codigo introducido. Estos datos respaldan la firma frente a la AEAT y tribunales.</p>

              <script>
                async function doSign() {
                  const otp = document.getElementById('otp').value.trim();
                  const fb = document.getElementById('fb');
                  const btn = document.getElementById('signBtn');
                  fb.textContent = '';
                  if (!/^\\d{6}$/.test(otp)) {
                    fb.className = 'err';
                    fb.textContent = 'El OTP debe ser de 6 digitos.';
                    return;
                  }
                  btn.disabled = true;
                  try {
                    const res = await fetch('/api/public/tpb/%s/sign', {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({ otp })
                    });
                    if (!res.ok) {
                      const text = await res.text();
                      fb.className = 'err';
                      fb.textContent = 'Error: ' + text;
                      btn.disabled = false;
                      return;
                    }
                    fb.className = 'ok';
                    fb.textContent = 'Acuerdo firmado correctamente. Puedes cerrar esta ventana.';
                    document.getElementById('signBox').style.display = 'none';
                  } catch (e) {
                    fb.className = 'err';
                    fb.textContent = 'Error de red: ' + e.message;
                    btn.disabled = false;
                  }
                }
              </script>
            </body>
            </html>
            """;
        String signBlock = canSign
                ? String.format("""
                    <div class="card" id="signBox">
                      <h2 style="font-size:16px;margin:0 0 8px">Firma con tu codigo OTP</h2>
                      <p>Introduce el codigo de 6 digitos que has recibido por email:</p>
                      <input id="otp" maxlength="6" inputmode="numeric" placeholder="123456">
                      <button id="signBtn" onclick="doSign()">Firmar acuerdo</button>
                      <div id="fb"></div>
                    </div>
                    """)
                : "<div class=\"card err\">No se puede firmar este enlace.</div>";
        return String.format(html,
                escape(stateMsg),
                token, token,
                signBlock,
                token);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Genera el icono de BENJAGEST (programa de facturación/asesoría):
 * fondo redondeado con degradado azul->teal de marca + un documento/factura
 * blanco con líneas y una insignia € amarilla. Saca PNGs por tamaño y los
 * empaqueta en un .ico de Windows (entradas PNG, válidas para jpackage).
 */
public class IconGen {

    static final Color BLUE   = new Color(0x23, 0x57, 0xF6);
    static final Color TEAL   = new Color(0x0A, 0xA6, 0xA6);
    static final Color CYAN   = new Color(0xD9, 0xFB, 0xFF);
    static final Color YELLOW = new Color(0xF8, 0xD3, 0x48);
    static final Color ROW    = new Color(0xB8, 0xC4, 0xDA);
    static final Color ROWLT  = new Color(0xD7, 0xDF, 0xEC);

    public static void main(String[] args) throws Exception {
        String outIco = args[0];
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        java.util.List<byte[]> pngs = new ArrayList<>();
        java.util.List<Integer> dims = new ArrayList<>();
        for (int s : sizes) {
            BufferedImage img = render(s);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            pngs.add(bos.toByteArray());
            dims.add(s);
            // PNG suelto del 256 por si hace falta para otros usos.
            if (s == 256) ImageIO.write(img, "png", new File(new File(outIco).getParent(), "benjagest-256.png"));
        }
        writeIco(outIco, dims, pngs);
        System.out.println("ICO escrito: " + outIco + " (" + sizes.length + " tamaños)");
    }

    static BufferedImage render(int S) {
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        double f = S / 256.0;

        // Fondo redondeado con degradado azul -> teal.
        double arc = 56 * f;
        g.setPaint(new GradientPaint(0, 0, BLUE, 0, S, TEAL));
        g.fill(new RoundRectangle2D.Double(0, 0, S, S, arc, arc));
        // Brillo superior sutil.
        g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 46), 0, (float) (S * 0.5), new Color(255, 255, 255, 0)));
        g.fill(new RoundRectangle2D.Double(0, 0, S, S, arc, arc));

        // Sombra suave del documento.
        if (S >= 48) {
            g.setColor(new Color(0, 0, 0, 38));
            g.fill(new RoundRectangle2D.Double(74 * f + 3 * f, 52 * f + 5 * f, 116 * f, 156 * f, 16 * f, 16 * f));
        }

        // Documento/factura blanco con esquina doblada.
        double dx = 74 * f, dy = 52 * f, dw = 116 * f, dh = 156 * f, dArc = 16 * f;
        double fold = 34 * f;
        Area doc = new Area(new RoundRectangle2D.Double(dx, dy, dw, dh, dArc, dArc));
        // recortar la esquina sup-derecha para el doblez
        Path2D corner = new Path2D.Double();
        corner.moveTo(dx + dw - fold, dy);
        corner.lineTo(dx + dw, dy + fold);
        corner.lineTo(dx + dw, dy);
        corner.closePath();
        doc.subtract(new Area(corner));
        g.setColor(Color.WHITE);
        g.fill(doc);
        // triángulo del doblez (cian)
        Path2D foldTri = new Path2D.Double();
        foldTri.moveTo(dx + dw - fold, dy);
        foldTri.lineTo(dx + dw - fold, dy + fold);
        foldTri.lineTo(dx + dw, dy + fold);
        foldTri.closePath();
        g.setColor(CYAN);
        g.fill(foldTri);

        // Filas de la factura.
        double rx = dx + 16 * f;
        double rw = dw - 32 * f;
        double rh = Math.max(2, 9 * f);
        g.setColor(ROW);
        roundLine(g, rx, dy + 40 * f, rw * 0.62, rh);          // "cabecera"
        g.setColor(ROWLT);
        roundLine(g, rx, dy + 62 * f, rw, rh);
        roundLine(g, rx, dy + 80 * f, rw, rh);
        roundLine(g, rx, dy + 98 * f, rw * 0.8, rh);

        // Insignia € (círculo amarillo + símbolo azul) abajo-derecha.
        double br = 52 * f;
        double bcx = dx + dw - 6 * f;
        double bcy = dy + dh - 10 * f;
        if (S >= 32) {
            g.setColor(new Color(0, 0, 0, 30));
            g.fill(new Ellipse2D.Double(bcx - br + 2 * f, bcy - br + 3 * f, br * 2, br * 2));
        }
        g.setColor(YELLOW);
        g.fill(new Ellipse2D.Double(bcx - br, bcy - br, br * 2, br * 2));
        g.setColor(new Color(0xE0, 0xB7, 0x22));
        g.setStroke(new BasicStroke((float) (3 * f)));
        g.draw(new Ellipse2D.Double(bcx - br, bcy - br, br * 2, br * 2));
        // símbolo €
        g.setColor(BLUE);
        Font font = new Font("Arial", Font.BOLD, (int) Math.round(70 * f));
        g.setFont(font);
        String e = "€";
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(e);
        int asc = fm.getAscent();
        int th = asc + fm.getDescent();
        g.drawString(e, (float) (bcx - tw / 2.0), (float) (bcy - th / 2.0 + asc));

        g.dispose();
        return img;
    }

    static void roundLine(Graphics2D g, double x, double y, double w, double h) {
        g.fill(new RoundRectangle2D.Double(x, y, w, h, h, h));
    }

    // ---- Escritura del .ico (entradas PNG) ----
    static void writeIco(String path, java.util.List<Integer> dims, java.util.List<byte[]> pngs) throws IOException {
        int n = pngs.size();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(path)))) {
            // ICONDIR
            writeLE16(out, 0);   // reserved
            writeLE16(out, 1);   // type = icon
            writeLE16(out, n);   // count
            int offset = 6 + n * 16;
            for (int i = 0; i < n; i++) {
                int s = dims.get(i);
                byte[] data = pngs.get(i);
                out.writeByte(s >= 256 ? 0 : s);  // width
                out.writeByte(s >= 256 ? 0 : s);  // height
                out.writeByte(0);                 // color count
                out.writeByte(0);                 // reserved
                writeLE16(out, 1);                // planes
                writeLE16(out, 32);               // bit count
                writeLE32(out, data.length);      // bytes in res
                writeLE32(out, offset);           // image offset
                offset += data.length;
            }
            for (byte[] data : pngs) out.write(data);
        }
    }

    static void writeLE16(DataOutputStream o, int v) throws IOException {
        o.writeByte(v & 0xFF); o.writeByte((v >> 8) & 0xFF);
    }
    static void writeLE32(DataOutputStream o, int v) throws IOException {
        o.writeByte(v & 0xFF); o.writeByte((v >> 8) & 0xFF);
        o.writeByte((v >> 16) & 0xFF); o.writeByte((v >> 24) & 0xFF);
    }
}

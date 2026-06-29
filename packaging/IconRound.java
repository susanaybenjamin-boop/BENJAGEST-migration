import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Enmascara una imagen a un CÍRCULO centrado (fuera = transparente). Quita la
 *  madera de las esquinas dejando solo la moneda redonda, con borde suavizado. */
public class IconRound {
    public static void main(String[] a) throws Exception {
        String src = a[0], out = a[1];
        double rf = a.length > 2 ? Double.parseDouble(a[2]) : 0.49; // fracción del ancho
        double cxf = a.length > 3 ? Double.parseDouble(a[3]) : 0.5;
        double cyf = a.length > 4 ? Double.parseDouble(a[4]) : 0.5;
        BufferedImage in = ImageIO.read(new File(src));
        int w = in.getWidth(), h = in.getHeight();
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        double cx = w * cxf, cy = h * cyf, r = Math.min(w, h) * rf;
        double aa = Math.max(1.5, w * 0.004); // ancho del borde suavizado
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
                double alpha; // 0..1
                if (d <= r - aa) alpha = 1.0;
                else if (d >= r) alpha = 0.0;
                else alpha = (r - d) / aa;
                if (alpha <= 0) { o.setRGB(x, y, 0); continue; }
                int argb = in.getRGB(x, y);
                int sa = (argb >>> 24);
                int na = (int) Math.round(sa * alpha);
                o.setRGB(x, y, (na << 24) | (argb & 0x00FFFFFF));
            }
        }
        ImageIO.write(o, "png", new File(out));
        // preview 256
        BufferedImage p = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = p.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(o, 0, 0, 256, 256, null); g.dispose();
        ImageIO.write(p, "png", new File(new File(out).getParent(), "round-preview-256.png"));
        System.out.println("ok r=" + rf);
    }
}

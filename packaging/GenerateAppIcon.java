import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Genera el icono de BENJAGEST en la paleta REAL de la app (app.css:
 * #2357f6 azul, #0aa6a6 turquesa, blanco, #d9fbff cian claro, #f8d348 dorado).
 * Diseno plano-con-volumen (degradados + sombra suave + brillo) tipo grafico
 * de barras ascendente: claro, grande y legible reducido a 16-32px.
 */
public class GenerateAppIcon {

    private static final Color BLUE = new Color(0x23, 0x57, 0xf6);
    private static final Color TEAL = new Color(0x0a, 0xa6, 0xa6);
    private static final Color LIGHT_CYAN = new Color(0xd9, 0xfb, 0xff);
    private static final Color GOLD_LIGHT = new Color(0xff, 0xe6, 0x8a);
    private static final Color GOLD = new Color(0xf8, 0xd3, 0x48);
    private static final Color GOLD_DARK = new Color(0xd9, 0xa5, 0x1a);
    private static final Color WHITE = Color.WHITE;
    private static final Color WHITE_SHADE = new Color(0xd7, 0xe6, 0xf3);
    private static final Color CYAN_SHADE = new Color(0xb7, 0xe9, 0xf2);

    public static void main(String[] args) throws Exception {
        int size = 1024;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        setup(g);

        int margin = 24;
        Ellipse2D circle = new Ellipse2D.Double(margin, margin, size - margin * 2, size - margin * 2);
        GradientPaint bgGrad = new GradientPaint(margin, margin, BLUE, size - margin, size - margin, TEAL);
        g.setPaint(bgGrad);
        g.fill(circle);
        // Brillo superior suave (da volumen al fondo, como un boton glossy).
        Paint sheen = new GradientPaint(0, margin, new Color(255, 255, 255, 70), 0, size * 0.55f, new Color(255, 255, 255, 0));
        g.setPaint(sheen);
        g.fill(circle);

        // ---- Sombra proyectada del grupo (barras + flecha) ----
        BufferedImage shadowLayer = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gs = shadowLayer.createGraphics();
        setup(gs);
        gs.setColor(new Color(0, 0, 0, 140));
        paintAxisAndBars(gs, true);
        paintArrow(gs, true);
        gs.dispose();
        BufferedImage blurred = blur(shadowLayer, 14);
        g.drawImage(blurred, 10, 16, null);

        // ---- Ejes + barras con volumen ----
        paintAxisAndBars(g, false);
        // ---- Flecha con volumen ----
        paintArrow(g, false);

        g.dispose();
        ImageIO.write(img, "png", new File("benjagest-icon-1024.png"));
        System.out.println("Generado packaging/benjagest-icon-1024.png (" + size + "x" + size + ")");
    }

    private static void setup(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static void paintAxisAndBars(Graphics2D g, boolean silhouette) {
        BasicStroke axisStroke = new BasicStroke(30, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        g.setStroke(axisStroke);
        if (silhouette) {
            g.draw(new Line2D.Double(300, 300, 300, 800));
            g.draw(new Line2D.Double(300, 800, 820, 800));
            fillBarSilhouette(g, 372, 800, 100, 120);
            fillBarSilhouette(g, 508, 800, 100, 240);
            fillBarSilhouette(g, 644, 800, 100, 380);
            return;
        }
        // Eje: linea con leve degradado + sombreado propio para no verse "plana".
        g.setPaint(new GradientPaint(300, 300, WHITE, 300, 800, WHITE_SHADE));
        g.draw(new Line2D.Double(300, 300, 300, 800));
        g.draw(new Line2D.Double(300, 800, 820, 800));

        drawBar3D(g, 372, 800, 100, 120, WHITE, WHITE_SHADE);
        drawBar3D(g, 508, 800, 100, 240, LIGHT_CYAN, CYAN_SHADE);
        drawBar3D(g, 644, 800, 100, 380, WHITE, WHITE_SHADE);
    }

    private static void fillBarSilhouette(Graphics2D g, int x, int baseline, int width, int height) {
        int arc = 18;
        g.fill(new RoundRectangle2D.Double(x, baseline - height, width, height, arc, arc));
    }

    /** Barra con degradado vertical (mas clara arriba) + veta lateral de sombra + brillo especular. */
    private static void drawBar3D(Graphics2D g, int x, int baseline, int width, int height, Color base, Color shade) {
        int arc = 18;
        double top = baseline - height;
        RoundRectangle2D bar = new RoundRectangle2D.Double(x, top, width, height, arc, arc);

        g.setPaint(new GradientPaint(x, (float) top, base, x, baseline, shade));
        g.fill(bar);

        // Veta de sombra en el borde derecho (simula volumen cilindrico).
        Shape oldClip = g.getClip();
        g.clip(bar);
        g.setPaint(new GradientPaint(x + width * 0.62f, 0, new Color(0, 0, 0, 0),
                x + width, 0, new Color(0, 0, 0, 45)));
        g.fill(new Rectangle2D.Double(x, top, width, height));
        // Brillo especular en el borde izquierdo.
        g.setPaint(new GradientPaint(x, 0, new Color(255, 255, 255, 120),
                x + width * 0.28f, 0, new Color(255, 255, 255, 0)));
        g.fill(new Rectangle2D.Double(x, top, width, height));
        g.setClip(oldClip);

        // Contorno sutil para dar nitidez a tamano pequeno.
        g.setStroke(new BasicStroke(3f));
        g.setColor(new Color(0, 0, 0, 30));
        g.draw(bar);
    }

    private static Path2D arrowPath() {
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(360, 470);
        arrow.lineTo(560, 300);
        arrow.lineTo(500, 300);
        arrow.lineTo(694, 220);
        arrow.lineTo(714, 400);
        arrow.lineTo(654, 350);
        arrow.lineTo(454, 520);
        arrow.closePath();
        return arrow;
    }

    private static void paintArrow(Graphics2D g, boolean silhouette) {
        Path2D arrow = arrowPath();
        if (silhouette) {
            g.fill(arrow);
            return;
        }
        // Degradado diagonal claro->oscuro para dar sensacion de metal/laca.
        g.setPaint(new GradientPaint(400, 250, GOLD_LIGHT, 700, 480, GOLD_DARK));
        g.fill(arrow);

        // Brillo especular a lo largo del cuerpo de la flecha.
        Shape oldClip = g.getClip();
        g.clip(arrow);
        Path2D sheen = new Path2D.Double();
        sheen.moveTo(390, 460);
        sheen.lineTo(560, 320);
        sheen.lineTo(590, 340);
        sheen.lineTo(420, 500);
        sheen.closePath();
        g.setPaint(new Color(255, 255, 255, 130));
        g.fill(sheen);
        g.setClip(oldClip);

        // Contorno para separar la flecha del fondo con nitidez.
        g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(GOLD_DARK);
        g.draw(arrow);
    }

    /** Blur tipo caja aplicado 3 veces (aproxima un gaussiano) para sombras suaves. */
    private static BufferedImage blur(BufferedImage src, int radius) {
        BufferedImage cur = src;
        int k = radius | 1;
        float v = 1f / (k * k);
        float[] data = new float[k * k];
        java.util.Arrays.fill(data, v);
        ConvolveOp op = new ConvolveOp(new Kernel(k, k, data), ConvolveOp.EDGE_NO_OP, null);
        for (int i = 0; i < 2; i++) {
            BufferedImage tmp = new BufferedImage(cur.getWidth(), cur.getHeight(), BufferedImage.TYPE_INT_ARGB);
            op.filter(cur, tmp);
            cur = tmp;
        }
        return cur;
    }
}

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Convierte un PNG cuadrado en un .ico multi-tamaño (entradas PNG) para jpackage. */
public class PngToIco {

    public static void main(String[] args) throws Exception {
        String src = args[0];
        String outIco = args[1];
        BufferedImage source = ImageIO.read(new File(src));
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        java.util.List<byte[]> pngs = new ArrayList<>();
        java.util.List<Integer> dims = new ArrayList<>();
        for (int s : sizes) {
            BufferedImage scaled = highQualityScale(source, s);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", bos);
            pngs.add(bos.toByteArray());
            dims.add(s);
            if (s == 256) ImageIO.write(scaled, "png",
                    new File(new File(outIco).getParent(), "benjagest-icon-256.png"));
        }
        writeIco(outIco, dims, pngs);
        System.out.println("ICO escrito: " + outIco + " desde " + source.getWidth() + "x" + source.getHeight());
    }

    /** Reduce por mitades (mejor nitidez) hasta acercarse al destino, luego paso final bicúbico. */
    static BufferedImage highQualityScale(BufferedImage src, int target) {
        BufferedImage cur = src;
        int w = src.getWidth(), h = src.getHeight();
        while (w / 2 >= target) {
            w /= 2; h /= 2;
            BufferedImage tmp = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = tmp.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(cur, 0, 0, w, h, null);
            g.dispose();
            cur = tmp;
        }
        BufferedImage out = new BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(cur, 0, 0, target, target, null);
        g.dispose();
        return out;
    }

    static void writeIco(String path, java.util.List<Integer> dims, java.util.List<byte[]> pngs) throws IOException {
        int n = pngs.size();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(path)))) {
            writeLE16(out, 0); writeLE16(out, 1); writeLE16(out, n);
            int offset = 6 + n * 16;
            for (int i = 0; i < n; i++) {
                int s = dims.get(i); byte[] data = pngs.get(i);
                out.writeByte(s >= 256 ? 0 : s);
                out.writeByte(s >= 256 ? 0 : s);
                out.writeByte(0); out.writeByte(0);
                writeLE16(out, 1); writeLE16(out, 32);
                writeLE32(out, data.length); writeLE32(out, offset);
                offset += data.length;
            }
            for (byte[] data : pngs) out.write(data);
        }
    }
    static void writeLE16(DataOutputStream o, int v) throws IOException { o.writeByte(v & 0xFF); o.writeByte((v >> 8) & 0xFF); }
    static void writeLE32(DataOutputStream o, int v) throws IOException {
        o.writeByte(v & 0xFF); o.writeByte((v >> 8) & 0xFF); o.writeByte((v >> 16) & 0xFF); o.writeByte((v >> 24) & 0xFF);
    }
}

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class BlockVisualizationMockup {
    private static final int W = 1970;
    private static final int H = 960;

    private static final Color BG = new Color(13, 17, 23);
    private static final Color PANEL = new Color(18, 24, 32);
    private static final Color PANEL_2 = new Color(21, 28, 38);
    private static final Color STROKE = new Color(50, 61, 76);
    private static final Color TEXT = new Color(236, 242, 249);
    private static final Color MUTED = new Color(144, 157, 176);
    private static final Color DIM = new Color(89, 101, 119);
    private static final Color GREEN = new Color(39, 201, 79);
    private static final Color ORANGE = new Color(255, 153, 25);
    private static final Color BLUE = new Color(84, 156, 255);
    private static final Color RED = new Color(255, 96, 96);

    public static void main(String[] args) throws Exception {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        drawBackground(g);
        drawHeader(g);
        drawMetricCards(g);
        drawMainStage(g);
        drawChain(g);
        drawInspector(g);
        drawFooter(g);

        g.dispose();
        ImageIO.write(image, "png", new File("output/block-visualization/block-visualization-concept.png"));
    }

    private static void drawBackground(Graphics2D g) {
        g.setPaint(new GradientPaint(0, 0, new Color(14, 19, 27), W, H, new Color(7, 10, 15)));
        g.fillRect(0, 0, W, H);

        for (int y = 0; y < H; y += 32) {
            g.setColor(new Color(255, 255, 255, y % 64 == 0 ? 9 : 5));
            g.drawLine(0, y, W, y);
        }
        for (int x = 0; x < W; x += 48) {
            g.setColor(new Color(255, 255, 255, x % 96 == 0 ? 7 : 3));
            g.drawLine(x, 0, x, H);
        }

        g.setPaint(new GradientPaint(0, 0, new Color(28, 44, 61, 120), 0, 260, new Color(28, 44, 61, 0)));
        g.fillRect(0, 0, W, 280);
        g.setPaint(new GradientPaint(0, H - 220, new Color(18, 47, 34, 0), 0, H, new Color(18, 47, 34, 95)));
        g.fillRect(0, H - 230, W, 230);

        g.setColor(new Color(255, 255, 255, 9));
        g.drawRoundRect(4, 6, W - 8, H - 12, 22, 22);
    }

    private static void drawHeader(Graphics2D g) {
        text(g, "区块链可视化", 42, 70, 32, Font.BOLD, TEXT);
        text(g, "实时区块流 · 高度、交易密度、确认深度与分叉风险", 42, 108, 18, Font.PLAIN, MUTED);

        pill(g, 1580, 45, 168, 38, "主链视图", GREEN, new Color(27, 78, 47), 15);
        pill(g, 1762, 45, 128, 38, "自动刷新", BLUE, new Color(26, 52, 90), 15);
    }

    private static void drawMetricCards(Graphics2D g) {
        int x = 42;
        String[][] cards = {
            {"当前高度", "878,432", "+1 / 14m", "green"},
            {"Mempool", "12,931 tx", "预计 2 个块", "orange"},
            {"平均出块", "9.8 min", "近 24 小时", "blue"},
            {"网络状态", "Healthy", "节点延迟 42ms", "green"}
        };

        for (int i = 0; i < cards.length; i++) {
            int w = i == 1 ? 245 : 225;
            metricCard(g, x, 142, w, 112, cards[i][0], cards[i][1], cards[i][2], color(cards[i][3]));
            x += w + 18;
        }
    }

    private static void drawMainStage(Graphics2D g) {
        RoundRectangle2D stage = new RoundRectangle2D.Double(42, 282, 1320, 540, 18, 18);
        g.setPaint(new GradientPaint(42, 282, PANEL, 1362, 822, new Color(14, 18, 25)));
        g.fill(stage);
        g.setColor(new Color(255, 255, 255, 16));
        g.draw(stage);

        text(g, "最近区块链路", 70, 328, 20, Font.BOLD, TEXT);
        text(g, "从左到右表示确认深度降低，区块面积映射交易数量", 70, 357, 14, Font.PLAIN, MUTED);

        pill(g, 1060, 315, 80, 34, "高度", BLUE, new Color(24, 43, 70), 13);
        pill(g, 1150, 315, 80, 34, "时间", new Color(128, 141, 160), new Color(34, 40, 50), 13);
        pill(g, 1240, 315, 88, 34, "交易量", new Color(128, 141, 160), new Color(34, 40, 50), 13);
    }

    private static void drawChain(Graphics2D g) {
        int y = 545;
        int[] xs = {128, 350, 575, 800, 1015, 1200};
        int[] heights = {878427, 878428, 878429, 878430, 878431, 878432};
        int[] txs = {2543, 1690, 2779, 4304, 3204, 1858};
        String[] ages = {"1h", "53m", "43m", "32m", "22m", "14m"};
        String[] states = {"CONFIRMED", "CONFIRMED", "CONFIRMED", "CONFIRMED", "PENDING", "LATEST"};
        Color[] cols = {new Color(91, 103, 121), new Color(91, 103, 121), BLUE, BLUE, ORANGE, GREEN};

        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < xs.length - 1; i++) {
            g.setPaint(new GradientPaint(xs[i], y, alpha(cols[i], 130), xs[i + 1], y, alpha(cols[i + 1], 150)));
            g.drawLine(xs[i] + 70, y, xs[i + 1] - 70, y);
        }

        for (int i = 0; i < xs.length; i++) {
            int size = 116 + Math.min(50, txs[i] / 130);
            int bw = size;
            int bh = size + 18;
            if (i == 5) {
                bw += 22;
                bh += 28;
            }
            drawBlock(g, xs[i], y, bw, bh, heights[i], txs[i], ages[i], states[i], cols[i], i == 5, i == 4);
        }

        drawFork(g, xs[1], y);
        drawDensityRail(g, 82, 735, 1185, 44);
    }

    private static void drawFork(Graphics2D g, int anchorX, int anchorY) {
        Path2D fork = new Path2D.Double();
        fork.moveTo(anchorX + 82, anchorY + 28);
        fork.curveTo(anchorX + 160, anchorY + 82, anchorX + 280, anchorY + 92, anchorX + 370, anchorY + 130);
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{8, 10}, 0));
        g.setColor(alpha(RED, 160));
        g.draw(fork);

        drawMiniBlock(g, anchorX + 386, anchorY + 98, 92, 70, RED);
        text(g, "候选分叉", anchorX + 498, anchorY + 126, 13, Font.BOLD, alpha(RED, 230));
        text(g, "2 confirmations behind", anchorX + 498, anchorY + 150, 12, Font.PLAIN, MUTED);
    }

    private static void drawBlock(Graphics2D g, int cx, int cy, int bw, int bh, int height, int tx,
                                  String age, String state, Color accent, boolean latest, boolean pending) {
        int x = cx - bw / 2;
        int y = cy - bh / 2;

        radial(g, cx, cy + 18, latest ? 230 : 160, alpha(accent, latest ? 80 : 35));

        Shape shadow = new RoundRectangle2D.Double(x + 9, y + 14, bw, bh, 18, 18);
        g.setColor(new Color(0, 0, 0, 70));
        g.fill(shadow);

        RoundRectangle2D box = new RoundRectangle2D.Double(x, y, bw, bh, 18, 18);
        g.setPaint(new GradientPaint(x, y, alpha(accent, latest ? 70 : 42), x + bw, y + bh, new Color(23, 29, 39, latest ? 245 : 226)));
        g.fill(box);
        g.setStroke(new BasicStroke(latest ? 2f : 1.5f));
        g.setColor(alpha(accent, latest ? 230 : 160));
        g.draw(box);

        Path2D top = new Path2D.Double();
        top.moveTo(x + 18, y);
        top.lineTo(x + bw - 18, y);
        top.quadTo(x + bw, y, x + bw, y + 18);
        top.lineTo(x + bw, y + 38);
        top.lineTo(x, y + 38);
        top.lineTo(x, y + 18);
        top.quadTo(x, y, x + 18, y);
        g.setColor(new Color(255, 255, 255, latest ? 22 : 14));
        g.fill(top);

        textCentered(g, state, cx, y + 35, 12, Font.BOLD, alpha(accent, pending || latest ? 245 : 170));
        textCentered(g, String.format("%,d", height), cx, y + 74, latest ? 22 : 18, Font.BOLD, latest ? TEXT : new Color(207, 217, 230));
        textCentered(g, tx + " txs", cx, y + 104, 15, Font.PLAIN, alpha(accent, latest || pending ? 245 : 200));
        textCentered(g, age + " ago", cx, y + bh - 26, 14, Font.PLAIN, MUTED);

        int barsX = x + 24;
        int barsY = y + bh - 66;
        int count = 6;
        for (int i = 0; i < count; i++) {
            int barH = 8 + (int) ((Math.sin((height + i) * 0.8) + 1) * 10);
            g.setColor(alpha(accent, 75 + i * 18));
            g.fillRoundRect(barsX + i * 12, barsY + 24 - barH, 7, barH, 4, 4);
        }
    }

    private static void drawMiniBlock(Graphics2D g, int x, int y, int w, int h, Color accent) {
        RoundRectangle2D box = new RoundRectangle2D.Double(x, y, w, h, 12, 12);
        g.setPaint(new GradientPaint(x, y, alpha(accent, 64), x + w, y + h, new Color(26, 29, 37, 220)));
        g.fill(box);
        g.setColor(alpha(accent, 170));
        g.draw(box);
        textCentered(g, "STALE", x + w / 2, y + 26, 11, Font.BOLD, alpha(accent, 230));
        textCentered(g, "878,428a", x + w / 2, y + 50, 13, Font.BOLD, TEXT);
    }

    private static void drawDensityRail(Graphics2D g, int x, int y, int w, int h) {
        RoundRectangle2D rail = new RoundRectangle2D.Double(x, y, w, h, 12, 12);
        g.setColor(new Color(9, 13, 19, 170));
        g.fill(rail);
        g.setColor(new Color(255, 255, 255, 18));
        g.draw(rail);
        text(g, "交易密度", x, y - 14, 13, Font.BOLD, MUTED);

        int[] vals = {22, 31, 45, 63, 52, 74, 91, 76, 55, 38, 29, 42, 60, 88, 69, 47, 33, 28};
        int cellW = (w - 30) / vals.length;
        for (int i = 0; i < vals.length; i++) {
            Color c = vals[i] > 70 ? ORANGE : vals[i] > 48 ? BLUE : GREEN;
            g.setColor(alpha(c, 55 + vals[i] * 2));
            g.fillRoundRect(x + 15 + i * cellW, y + 12, Math.max(10, cellW - 5), h - 24, 6, 6);
        }
    }

    private static void drawInspector(Graphics2D g) {
        int x = 1390;
        int y = 142;
        int w = 538;
        int h = 680;
        RoundRectangle2D panel = new RoundRectangle2D.Double(x, y, w, h, 18, 18);
        g.setPaint(new GradientPaint(x, y, PANEL_2, x + w, y + h, new Color(14, 19, 27)));
        g.fill(panel);
        g.setColor(new Color(255, 255, 255, 18));
        g.draw(panel);

        text(g, "区块详情", x + 34, y + 48, 22, Font.BOLD, TEXT);
        pill(g, x + 390, y + 25, 104, 34, "LATEST", GREEN, new Color(23, 71, 42), 13);

        text(g, "878,432", x + 34, y + 112, 48, Font.BOLD, TEXT);
        text(g, "1858 txs · 14m ago · 1.34 MB", x + 38, y + 146, 15, Font.PLAIN, MUTED);

        drawHashRow(g, x + 34, y + 196, "Hash", "00000000000000000002a9f4...b83c7e12", GREEN);
        drawHashRow(g, x + 34, y + 254, "Prev Hash", "0000000000000000000144d2...6e91af03", BLUE);
        drawHashRow(g, x + 34, y + 312, "Merkle Root", "a7c9d21f85e4cc21b7a0...9d01f0aa", ORANGE);

        text(g, "确认深度", x + 34, y + 390, 15, Font.BOLD, TEXT);
        for (int i = 0; i < 6; i++) {
            int cx = x + 38 + i * 44;
            g.setColor(i == 0 ? GREEN : alpha(BLUE, 120 + i * 15));
            g.fill(new Ellipse2D.Double(cx, y + 412, 18, 18));
            if (i < 5) {
                g.setStroke(new BasicStroke(2f));
                g.setColor(alpha(BLUE, 90));
                g.drawLine(cx + 18, y + 421, cx + 44, y + 421);
            }
        }
        text(g, "当前最新块，等待更多确认", x + 325, y + 426, 14, Font.PLAIN, MUTED);

        text(g, "交易体积分布", x + 34, y + 482, 15, Font.BOLD, TEXT);
        for (int i = 0; i < 22; i++) {
            int col = i % 11;
            int row = i / 11;
            int val = 35 + (int) ((Math.sin(i * 1.7) + 1) * 44);
            Color c = val > 78 ? ORANGE : val > 58 ? BLUE : GREEN;
            g.setColor(alpha(c, 90 + val));
            g.fillRoundRect(x + 34 + col * 42, y + 504 + row * 35, 30, 24, 6, 6);
        }

        statPair(g, x + 34, y + 620, "Nonce", "3,982,411,507");
        statPair(g, x + 270, y + 620, "Difficulty", "83.1 T");
    }

    private static void drawHashRow(Graphics2D g, int x, int y, String label, String value, Color accent) {
        text(g, label, x, y, 13, Font.BOLD, MUTED);
        g.setColor(new Color(8, 12, 18, 175));
        g.fillRoundRect(x, y + 12, 470, 34, 9, 9);
        g.setColor(alpha(accent, 170));
        g.drawRoundRect(x, y + 12, 470, 34, 9, 9);
        text(g, value, x + 14, y + 35, 14, Font.PLAIN, new Color(204, 216, 230));
    }

    private static void drawFooter(Graphics2D g) {
        int y = 870;
        legend(g, 810, y, GREEN, "最新区块");
        legend(g, 940, y, ORANGE, "待确认");
        legend(g, 1068, y, BLUE, "确认中");
        legend(g, 1192, y, new Color(104, 116, 135), "历史区块");
        legend(g, 1326, y, RED, "候选分叉");

        textCentered(g, "设计重点：区块状态用颜色，确认深度用位置，交易压力用面积和热力条，技术细节放入右侧检查器", W / 2, 930, 15, Font.PLAIN, MUTED);
    }

    private static void metricCard(Graphics2D g, int x, int y, int w, int h, String label, String value, String sub, Color accent) {
        RoundRectangle2D card = new RoundRectangle2D.Double(x, y, w, h, 14, 14);
        g.setPaint(new GradientPaint(x, y, new Color(22, 29, 38), x + w, y + h, new Color(14, 19, 27)));
        g.fill(card);
        g.setColor(new Color(255, 255, 255, 15));
        g.draw(card);
        g.setColor(alpha(accent, 210));
        g.fillRoundRect(x + 18, y + 18, 5, 42, 4, 4);
        text(g, label, x + 36, y + 34, 13, Font.BOLD, MUTED);
        text(g, value, x + 36, y + 72, 25, Font.BOLD, TEXT);
        text(g, sub, x + 36, y + 98, 13, Font.PLAIN, alpha(accent, 230));
    }

    private static void statPair(Graphics2D g, int x, int y, String label, String value) {
        text(g, label, x, y, 13, Font.BOLD, MUTED);
        text(g, value, x, y + 30, 19, Font.BOLD, TEXT);
    }

    private static void legend(Graphics2D g, int x, int y, Color c, String label) {
        g.setColor(c);
        g.fill(new Ellipse2D.Double(x, y - 10, 12, 12));
        text(g, label, x + 22, y + 1, 14, Font.PLAIN, new Color(203, 213, 226));
    }

    private static void pill(Graphics2D g, int x, int y, int w, int h, String label, Color accent, Color fill, int size) {
        g.setColor(fill);
        g.fillRoundRect(x, y, w, h, h, h);
        g.setColor(alpha(accent, 160));
        g.drawRoundRect(x, y, w, h, h, h);
        textCentered(g, label, x + w / 2, y + h / 2 + size / 2 - 3, size, Font.BOLD, accent);
    }

    private static void text(Graphics2D g, String s, int x, int y, int size, int style, Color c) {
        g.setFont(font(size, style));
        g.setColor(c);
        g.drawString(s, x, y);
    }

    private static void textCentered(Graphics2D g, String s, int cx, int y, int size, int style, Color c) {
        g.setFont(font(size, style));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(c);
        g.drawString(s, cx - fm.stringWidth(s) / 2, y);
    }

    private static Font font(int size, int style) {
        String[] preferred = {"Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC", "SimHei", "Arial"};
        String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        for (String p : preferred) {
            for (String f : families) {
                if (f.equalsIgnoreCase(p)) {
                    return new Font(f, style, size);
                }
            }
        }
        return new Font(Font.SANS_SERIF, style, size);
    }

    private static void radial(Graphics2D g, int cx, int cy, int radius, Color color) {
        BufferedImage glow = new BufferedImage(radius * 2, radius * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gg = glow.createGraphics();
        gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int r = radius; r > 0; r--) {
            float t = r / (float) radius;
            int a = (int) (color.getAlpha() * Math.pow(1 - t, 1.8));
            gg.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, a))));
            gg.fillOval(radius - r, radius - r, r * 2, r * 2);
        }
        gg.dispose();
        g.drawImage(glow, cx - radius, cy - radius, null);
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    private static Color color(String key) {
        switch (key) {
            case "green": return GREEN;
            case "orange": return ORANGE;
            case "blue": return BLUE;
            default: return MUTED;
        }
    }
}

/*
 * 깨워드림 합성 시연 영상 렌더러(JDK 17 단일 파일 실행 + ffmpeg).
 * 앱 화면은 Automotive 에뮬레이터에서 '깨워드림 시연'을 녹화한 것(adb shell screenrecord --size 1920x1080 --time-limit 180)이고,
 * 실내 장면(무드등·화면 둘레 빛·핸들 열선·찬바람)은 녹화 시각에 맞춘 합성 연출이다. 앱의 음성 안내 문구는 AI 음성 합성
 * (Higgsfield 의 Inworld Text to Speech, 한국어 음성 Yoona)으로 만들어 위쪽 자막과 같은 시각에 넣고, 경고음은 앱의 경고음 패턴을 재현한다.
 *
 * 실행: java -Xmx6g WakeVideo.java <녹화.mp4> <실내 사진(화면 정면)> <핸들 열선 꺼짐 사진> <핸들 열선 켜짐 사진> <출력.mp4> edl.txt voice.txt
 *  - edl.txt  : 장면 목록(종류 시작 끝 줌시작 줌끝 줌중심x 줌중심y, 시각은 녹화 기준 초)
 *  - voice.txt: 음성·경고음(종류 녹화시각 길이 자막)
 * 소리 트랙(음성·경고음)은 voice.txt 의 시각을 출력 시각으로 바꿔 ffmpeg adelay/amix 로 따로 만든 뒤 합친다.
 */
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.DoubleBinaryOperator;

/** WakeMate 합성 시연 영상: 실내 사진 + 녹화한 앱 화면 + 단계별 실내 효과(무드등·화면 빛·핸들 열선·찬바람) + 음성 자막 + 하단 상태. */
public class WakeVideo {
    static final int W = 1920, H = 1080, FPS = 30, SW = 1920, SH = 1080;
    // 녹화본 기준 시각(초): 주의, 경고(위험지수), 자극 작동(경고 알림 쓰기 완료), 위험
    static final double T_S1 = 40.5, T_S2 = 84.5, T_STIM = 101.4, T_S3 = 155.0;

    static final Color C_NORMAL = new Color(0x02C265), C_CAUTION = new Color(0xFFC224), C_WARN = new Color(0xFF8A00), C_DANGER = new Color(0xFE3D16);
    static final Color AMB_BLUE = new Color(0x2E9BFF), AMB_ALERT = new Color(0xFF3B1F), INK = new Color(0x14161A);

    static Color tone(int st) { return st <= 0 ? C_NORMAL : st == 1 ? C_CAUTION : st == 2 ? C_WARN : C_DANGER; }
    static int stageAt(double ts) { return ts >= T_S3 ? 3 : ts >= T_S2 ? 2 : ts >= T_S1 ? 1 : 0; }
    static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    static double smooth(double v) { v = clamp01(v); return v * v * (3 - 2 * v); }
    static Color mix(Color a, Color b, double t) { t = clamp01(t); return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t), (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t), (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t)); }
    static Color alpha(Color c, double a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(255 * clamp01(a))); }
    static final double[] ONSET = {0, T_S1, T_S2, T_S3};
    static Color toneAt(double ts) { int st = stageAt(ts); if (st == 0) return C_NORMAL; return mix(tone(st - 1), tone(st), smooth((ts - ONSET[st]) / 0.45)); }
    static double glowAt(double ts) { double[] lv = {0, 0.55, 0.72, 0.9}; int st = stageAt(ts); if (st == 0) return 0; return lv[st - 1] + (lv[st] - lv[st - 1]) * smooth((ts - ONSET[st]) / 0.45); }
    static double stimAt(double ts) { return smooth((ts - T_STIM) / 1.1); }

    static Font font(String ps, float size) { Font f = new Font(ps, Font.PLAIN, 10); if (!f.getFontName().equalsIgnoreCase(ps)) f = new Font("Apple SD Gothic Neo", ps.contains("Bold") ? Font.BOLD : Font.PLAIN, 10); return f.deriveFont(size); }
    static Font bold(float s) { return font("AppleSDGothicNeo-Bold", s); }
    static Font semi(float s) { return font("AppleSDGothicNeo-SemiBold", s); }
    static Font med(float s) { return font("AppleSDGothicNeo-Medium", s); }

    // ---------------- 빛 마스크 ----------------
    static class Mask { int[] idx; float[] v; }
    static Mask mask(DoubleBinaryOperator f, int x0, int y0, int x1, int y1) {
        x0 = Math.max(0, x0); y0 = Math.max(0, y0); x1 = Math.min(W, x1); y1 = Math.min(H, y1);
        int[] idx = new int[(x1 - x0) * (y1 - y0)]; float[] v = new float[idx.length]; int n = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) { double m = f.applyAsDouble(x + 0.5, y + 0.5); if (m > 0.004) { idx[n] = y * W + x; v[n] = (float) m; n++; } }
        Mask r = new Mask(); r.idx = Arrays.copyOf(idx, n); r.v = Arrays.copyOf(v, n); return r;
    }
    static void add(int[] px, Mask m, Color c, double k) {
        if (k <= 0.002) return;
        int cr = c.getRed(), cg = c.getGreen(), cb = c.getBlue();
        for (int n = 0; n < m.idx.length; n++) {
            double a = m.v[n] * k; int i = m.idx[n]; int p = px[i];
            int r = Math.min(255, ((p >> 16) & 255) + (int) (cr * a)), g = Math.min(255, ((p >> 8) & 255) + (int) (cg * a)), b = Math.min(255, (p & 255) + (int) (cb * a));
            px[i] = (r << 16) | (g << 8) | b;
        }
    }
    static double segDist(double px, double py, double ax, double ay, double bx, double by) {
        double vx = bx - ax, vy = by - ay, t = ((px - ax) * vx + (py - ay) * vy) / (vx * vx + vy * vy); t = Math.max(0, Math.min(1, t));
        double dx = px - (ax + t * vx), dy = py - (ay + t * vy); return Math.sqrt(dx * dx + dy * dy);
    }
    static double rectDist(double x, double y, double x0, double y0, double x1, double y1) {
        double dx = Math.max(Math.max(x0 - x, 0), x - x1), dy = Math.max(Math.max(y0 - y, 0), y - y1); return Math.sqrt(dx * dx + dy * dy);
    }

    // ---------------- 원근 변환 ----------------
    static double[] homography(double[] s, double[] d) {
        double[][] A = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = s[2 * i], y = s[2 * i + 1], u = d[2 * i], v = d[2 * i + 1];
            A[2 * i] = new double[]{x, y, 1, 0, 0, 0, -u * x, -u * y, u};
            A[2 * i + 1] = new double[]{0, 0, 0, x, y, 1, -v * x, -v * y, v};
        }
        for (int c = 0; c < 8; c++) {
            int piv = c; for (int r = c + 1; r < 8; r++) if (Math.abs(A[r][c]) > Math.abs(A[piv][c])) piv = r;
            double[] t = A[c]; A[c] = A[piv]; A[piv] = t;
            for (int r = 0; r < 8; r++) if (r != c) { double f = A[r][c] / A[c][c]; for (int k = c; k < 9; k++) A[r][k] -= f * A[c][k]; }
        }
        double[] h = new double[9]; for (int i = 0; i < 8; i++) h[i] = A[i][8] / A[i][i]; h[8] = 1; return h;
    }
    static int bilinear(int[] src, int sw, int sh, double u, double v) {
        u = Math.max(0, Math.min(sw - 1.001, u)); v = Math.max(0, Math.min(sh - 1.001, v));
        int x = (int) u, y = (int) v; double fx = u - x, fy = v - y;
        int p00 = src[y * sw + x], p10 = src[y * sw + x + 1], p01 = src[(y + 1) * sw + x], p11 = src[(y + 1) * sw + x + 1];
        int r = (int) (((p00 >> 16) & 255) * (1 - fx) * (1 - fy) + ((p10 >> 16) & 255) * fx * (1 - fy) + ((p01 >> 16) & 255) * (1 - fx) * fy + ((p11 >> 16) & 255) * fx * fy);
        int g = (int) (((p00 >> 8) & 255) * (1 - fx) * (1 - fy) + ((p10 >> 8) & 255) * fx * (1 - fy) + ((p01 >> 8) & 255) * (1 - fx) * fy + ((p11 >> 8) & 255) * fx * fy);
        int b = (int) ((p00 & 255) * (1 - fx) * (1 - fy) + (p10 & 255) * fx * (1 - fy) + (p01 & 255) * (1 - fx) * fy + (p11 & 255) * fx * fy);
        return (r << 16) | (g << 8) | b;
    }
    static void warp(int[] dst, int[] src, double[] q, double bright) {
        double[] h = homography(q, new double[]{0, 0, SW, 0, SW, SH, 0, SH});
        double minX = Math.min(Math.min(q[0], q[2]), Math.min(q[4], q[6])), maxX = Math.max(Math.max(q[0], q[2]), Math.max(q[4], q[6]));
        double minY = Math.min(Math.min(q[1], q[3]), Math.min(q[5], q[7])), maxY = Math.max(Math.max(q[1], q[3]), Math.max(q[5], q[7]));
        double scale = Math.hypot(q[2] - q[0], q[3] - q[1]) / SW;
        for (int y = Math.max(0, (int) minY - 1); y <= Math.min(H - 1, (int) maxY + 1); y++)
            for (int x = Math.max(0, (int) minX - 1); x <= Math.min(W - 1, (int) maxX + 1); x++) {
                double X = x + 0.5, Y = y + 0.5, wq = h[6] * X + h[7] * Y + 1, u = (h[0] * X + h[1] * Y + h[2]) / wq, v = (h[3] * X + h[4] * Y + h[5]) / wq;
                double edge = Math.min(Math.min(u, SW - u), Math.min(v, SH - v)) * scale + 0.5;
                if (edge <= 0) continue;
                int s = bilinear(src, SW, SH, u, v), i = y * W + x, d = dst[i];
                double a = Math.min(1, edge);
                int r = (int) (((s >> 16) & 255) * bright * a + ((d >> 16) & 255) * (1 - a)), g = (int) (((s >> 8) & 255) * bright * a + ((d >> 8) & 255) * (1 - a)), b = (int) ((s & 255) * bright * a + (d & 255) * (1 - a));
                dst[i] = (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
            }
    }

    // ---------------- 녹화본 읽기 ----------------
    static class Src {
        final InputStream in; final byte[] buf = new byte[SW * SH * 3]; int idx = -1; boolean dirty = true;
        final BufferedImage img = new BufferedImage(SW, SH, BufferedImage.TYPE_INT_RGB); final int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        Src(String path) throws IOException {
            Process p = new ProcessBuilder("ffmpeg", "-v", "error", "-i", path, "-vf", "fps=30", "-f", "rawvideo", "-pix_fmt", "rgb24", "-").redirectError(new File(System.getProperty("java.io.tmpdir"), "wm-dec.log")).start();
            in = new BufferedInputStream(p.getInputStream(), 1 << 22);
        }
        boolean next() throws IOException { int off = 0; while (off < buf.length) { int n = in.read(buf, off, buf.length - off); if (n < 0) return false; off += n; } idx++; dirty = true; return true; }
        int[] at(double t) throws IOException {
            int want = (int) Math.round(t * FPS); while (idx < want) if (!next()) break;
            if (dirty) { for (int i = 0, j = 0; i < px.length; i++, j += 3) px[i] = ((buf[j] & 255) << 16) | ((buf[j + 1] & 255) << 8) | (buf[j + 2] & 255); dirty = false; }
            return px;
        }
    }

    // ---------------- 장면 정의 ----------------
    enum Kind { TITLE, MAIN, CUT, END }
    record Seg(Kind k, double s0, double dur, double z0, double z1, double fx, double fy) {}
    record Voice(String kind, double ts, double dur, String text) {}
    static final List<Voice> VOICES = new ArrayList<>();

    static final double SX = W / 1400.0, SY = H / 788.0;
    static final double UI_X = 204.4 * SX, UI_Y = 108.0 * SY, UI_W = (1205.1 - 204.4) * SX, UI_H = (665.1 - 108.0) * SY;
    static final double BZ_X0 = 183 * SX, BZ_Y0 = 91 * SY, BZ_X1 = 1236 * SX, BZ_Y1 = 686 * SY;
    static final double[] CUT_QUAD = {586, 310, 1037, 321, 996, 601, 568, 545};

    static BufferedImage base3, wheelOff, wheelOn;
    static int[] base3px;
    static Mask bezel, strips, stripCore;

    public static void main(String[] a) throws Exception {
        String rec = a[0], cabin = a[1], off = a[2], on = a[3], out = a[4], edlPath = a[5], voicePath = a[6];
        base3 = scaled(ImageIO.read(new File(cabin)), W, H); base3px = ((DataBufferInt) base3.getRaster().getDataBuffer()).getData();
        wheelOff = toRgb(ImageIO.read(new File(off))); wheelOn = toRgb(ImageIO.read(new File(on)));
        // 화면 둘레 빛: 차량 화면에서 실내로 번지는 빛(앞유리 쪽은 약하게)
        bezel = mask((x, y) -> { double d = rectDist(x, y, BZ_X0, BZ_Y0, BZ_X1, BZ_Y1); if (d <= 0) return 0; double up = y < BZ_Y0 ? 0.45 : 1.0; return up * (0.62 * Math.exp(-d / 15) + 0.2 * Math.exp(-d / 55)); },
            (int) BZ_X0 - 220, (int) BZ_Y0 - 160, (int) BZ_X1 + 220, (int) BZ_Y1 + 160);
        // 대시보드 무드등 띠: 사진 속 대시보드 박음질·홈 선을 따라(왼쪽은 핸들 뒤로 가려진 부분 제외)
        double[][] segs = {{1702, 370, 1920, 370}, {1702, 504, 1920, 504}, {136, 364, 247, 364}};
        DoubleBinaryOperator halo = (x, y) -> { double m = 0; for (double[] s : segs) { if (s[0] < 500 && x < 132) continue; double d = segDist(x, y, s[0], s[1], s[2], s[3]); m = Math.max(m, 0.8 * Math.exp(-d / 6) + 0.3 * Math.exp(-d / 28)); } return m; };
        DoubleBinaryOperator core = (x, y) -> { double m = 0; for (double[] s : segs) { if (s[0] < 500 && x < 132) continue; double d = segDist(x, y, s[0], s[1], s[2], s[3]); m = Math.max(m, Math.exp(-(d / 1.7) * (d / 1.7))); } return m; };
        strips = mask(halo, 60, 300, 1920, 600); stripCore = mask(core, 60, 300, 1920, 600);

        List<Seg> edl = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(edlPath), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue; String[] p = line.trim().split("\\s+");
            double s0 = Double.parseDouble(p[1]), s1 = Double.parseDouble(p[2]);
            edl.add(new Seg(Kind.valueOf(p[0]), s0, s1 - s0, Double.parseDouble(p[3]), Double.parseDouble(p[4]), Double.parseDouble(p[5]), Double.parseDouble(p[6])));
        }
        for (String line : Files.readAllLines(Path.of(voicePath), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue; String[] p = line.split("\t");
            VOICES.add(new Voice(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), p[3]));
        }

        Src src = new Src(rec);
        Process enc = new ProcessBuilder("ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", W + "x" + H, "-r", "" + FPS, "-i", "-",
            "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out).redirectError(new File(System.getProperty("java.io.tmpdir"), "wm-enc.log")).start();
        OutputStream eo = new BufferedOutputStream(enc.getOutputStream(), 1 << 22);
        byte[] ob = new byte[W * H * 3];

        BufferedImage scene = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB); int[] spx = ((DataBufferInt) scene.getRaster().getDataBuffer()).getData();
        BufferedImage frame = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB); int[] fpx = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
        int[] prev = new int[W * H]; boolean hasPrev = false;
        int total = 0; long t0 = System.currentTimeMillis();
        for (int si = 0; si < edl.size(); si++) {
            Seg sg = edl.get(si); int n = (int) Math.round(sg.dur() * FPS);
            for (int f = 0; f < n; f++) {
                double tIn = f / (double) FPS, u = n <= 1 ? 1 : f / (double) (n - 1), ts = sg.s0() + tIn;
                Graphics2D g = frame.createGraphics(); hints(g);
                switch (sg.k()) {
                    case TITLE -> title(g, tIn);
                    case END -> end(g, tIn, sg.dur());
                    case MAIN -> {
                        int[] ui = src.at(ts);
                        mainScene(scene, spx, ui, ts);
                        double z = sg.z0() + (sg.z1() - sg.z0()) * smooth(u);
                        drawZoomed(g, scene, z, sg.fx(), sg.fy());
                        overlays(g, ts, false);
                    }
                    case CUT -> {
                        int[] ui = src.at(ts);
                        g.dispose();
                        cutScene(frame, fpx, ui, u, tIn);
                        g = frame.createGraphics(); hints(g);
                        overlays(g, ts, true);
                    }
                }
                g.dispose();
                int xf = (int) (0.4 * FPS);
                if (hasPrev && f < xf) { double k = smooth((f + 1) / (double) (xf + 1)); for (int i = 0; i < fpx.length; i++) fpx[i] = lerpPx(prev[i], fpx[i], k); }
                for (int i = 0, j = 0; i < fpx.length; i++, j += 3) { int p = fpx[i]; ob[j] = (byte) (p >> 16); ob[j + 1] = (byte) (p >> 8); ob[j + 2] = (byte) p; }
                eo.write(ob); total++;
                if (f == n - 1) { System.arraycopy(fpx, 0, prev, 0, fpx.length); hasPrev = true; }
            }
            System.out.printf("segment %d (%s) done, frames=%d, %.1fs%n", si, sg.k(), total, (System.currentTimeMillis() - t0) / 1000.0);
        }
        eo.close(); int code = enc.waitFor();
        System.out.println("encoded frames=" + total + " exit=" + code);
    }

    static int lerpPx(int a, int b, double k) {
        int r = (int) (((a >> 16) & 255) * (1 - k) + ((b >> 16) & 255) * k), g = (int) (((a >> 8) & 255) * (1 - k) + ((b >> 8) & 255) * k), bl = (int) ((a & 255) * (1 - k) + (b & 255) * k);
        return (r << 16) | (g << 8) | bl;
    }
    static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }
    static BufferedImage toRgb(BufferedImage s) { BufferedImage d = new BufferedImage(s.getWidth(), s.getHeight(), BufferedImage.TYPE_INT_RGB); Graphics2D g = d.createGraphics(); g.drawImage(s, 0, 0, null); g.dispose(); return d; }
    static BufferedImage scaled(BufferedImage s, int w, int h) {
        BufferedImage d = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB); Graphics2D g = d.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC); g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(s, 0, 0, w, h, null); g.dispose(); return d;
    }
    static void drawZoomed(Graphics2D g, BufferedImage scene, double z, double fx, double fy) {
        if (Math.abs(z - 1) < 1e-4) { g.drawImage(scene, 0, 0, null); return; }
        AffineTransform at = new AffineTransform(); at.translate(fx, fy); at.scale(z, z); at.translate(-fx, -fy);
        g.drawImage(scene, at, null);
    }

    /** 메인: 실내 사진 + 화면(녹화) + 화면 둘레 단계 빛 + 대시보드 무드등. */
    static void mainScene(BufferedImage scene, int[] spx, int[] ui, double ts) {
        System.arraycopy(base3px, 0, spx, 0, spx.length);
        Graphics2D g = scene.createGraphics(); hints(g);
        BufferedImage uiImg = new BufferedImage(SW, SH, BufferedImage.TYPE_INT_RGB); System.arraycopy(ui, 0, ((DataBufferInt) uiImg.getRaster().getDataBuffer()).getData(), 0, ui.length);
        Shape clip = new RoundRectangle2D.Double(UI_X, UI_Y, UI_W, UI_H, 16, 16);
        g.setClip(clip); g.drawImage(uiImg, (int) Math.round(UI_X), (int) Math.round(UI_Y), (int) Math.round(UI_W), (int) Math.round(UI_H), null);
        g.setClip(null);
        g.setPaint(new GradientPaint((float) UI_X, (float) UI_Y, new Color(255, 255, 255, 16), (float) (UI_X + UI_W * 0.6), (float) (UI_Y + UI_H), new Color(255, 255, 255, 0)));
        g.fill(clip);
        g.dispose();
        add(spx, bezel, toneAt(ts), glowAt(ts));
        double s = stimAt(ts);
        add(spx, strips, mix(AMB_BLUE, AMB_ALERT, s), 0.55 + 0.6 * s);
        add(spx, stripCore, mix(new Color(170, 215, 255), new Color(255, 205, 180), s), 0.55 + 0.35 * s);
    }

    /** 실내 컷: 핸들 열선이 켜지고 송풍구에서 운전석 쪽으로 찬바람이 나온다. 차량 화면에는 같은 시각의 앱 화면을 원근에 맞춰 넣는다. */
    static void cutScene(BufferedImage frame, int[] fpx, int[] ui, double u, double tIn) {
        double z = 1.0 + 0.06 * smooth(u), w = 1086 / z, h = 611 / z;
        double x0 = Math.max(0, 1052 - w), y0 = Math.max(0, Math.min(1448 - h, 505 - h / 2));
        AffineTransform at = new AffineTransform(); at.scale(W / w, H / h); at.translate(-x0, -y0);
        Graphics2D g = frame.createGraphics(); hints(g);
        g.drawImage(wheelOff, at, null);
        double heat = smooth((tIn - 0.5) / 2.0);
        if (heat > 0) { g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) heat)); g.drawImage(wheelOn, at, null); g.setComposite(AlphaComposite.SrcOver); }
        g.dispose();
        double[] q = new double[8]; at.transform(CUT_QUAD, 0, q, 0, 4);
        warp(fpx, ui, q, 0.97);
        g = frame.createGraphics(); hints(g);
        // 송풍구(사진 좌표 x 600~990, y 625~700)에서 운전석 쪽(왼쪽)으로 흐르는 찬바람
        double air = smooth(tIn / 0.8);
        Graphics2D a = (Graphics2D) g.create(); a.transform(at);
        BasicStroke outer = new BasicStroke(9f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND), inner = new BasicStroke(3.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND);
        for (int i = 0; i < 9; i++) {
            double xs = 765 - i * 16, ys = 650 + i * 5.5, ph = (tIn * 0.55 + i * 0.173) % 1.0;
            for (int k = 0; k < 16; k++) {
                double s0 = ph - 0.34 + k * 0.021, s1 = s0 + 0.021; if (s1 < 0 || s0 > 1) continue; s0 = Math.max(0, s0); s1 = Math.min(1, s1);
                double ax = xs - 270 * s0, ay = ys + 34 * s0 + 9 * Math.sin(5.5 * s0 + i * 0.9), bx = xs - 270 * s1, by = ys + 34 * s1 + 9 * Math.sin(5.5 * s1 + i * 0.9);
                double env = Math.sin(Math.PI * (s0 + s1) / 2) * (0.2 + 0.8 * (k / 15.0)) * air;
                a.setStroke(outer); a.setColor(alpha(new Color(90, 170, 255), 0.26 * env)); a.draw(new Line2D.Double(ax, ay, bx, by));
                a.setStroke(inner); a.setColor(alpha(new Color(230, 244, 255), 0.62 * env)); a.draw(new Line2D.Double(ax, ay, bx, by));
            }
        }
        a.dispose();
        callout(g, at.transform(new Point2D.Double(127, 412), null), 44, 200, "핸들 열선 3단", C_WARN, smooth((tIn - 1.6) / 0.5));
        callout(g, at.transform(new Point2D.Double(700, 655), null), 1060, 883, "외기 전환 · 풍량 7단 · 21.5℃", AMB_BLUE, smooth((tIn - 2.3) / 0.5));
        g.dispose();
    }

    /** 설명표: 상자(왼쪽 끝 boxX, 세로 가운데 boxCY)와 가리키는 점(anchor)을 선으로 잇는다. */
    static void callout(Graphics2D g, Point2D anchor, double boxX, double boxCY, String text, Color dot, double k) {
        if (k <= 0) return;
        Composite old = g.getComposite(); g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) k));
        g.setFont(bold(34f)); FontMetrics fm = g.getFontMetrics(); int tw = fm.stringWidth(text);
        double bw = tw + 76, bh = 66, bx0 = boxX, bx1 = boxX + bw, by0 = boxCY - bh / 2, by1 = boxCY + bh / 2;
        double ax = anchor.getX(), ay = anchor.getY(), tx, ty;
        if (ax < bx0) { tx = bx0; ty = boxCY; } else if (ax > bx1) { tx = bx1; ty = boxCY; } else { tx = ax; ty = ay < by0 ? by0 : by1; }
        g.setColor(new Color(255, 255, 255, 225)); g.setStroke(new BasicStroke(2.5f)); g.draw(new Line2D.Double(ax, ay, tx, ty));
        g.setColor(dot); g.fill(new Ellipse2D.Double(ax - 9, ay - 9, 18, 18)); g.setColor(Color.WHITE); g.setStroke(new BasicStroke(3f)); g.draw(new Ellipse2D.Double(ax - 9, ay - 9, 18, 18));
        RoundRectangle2D box = new RoundRectangle2D.Double(bx0, by0, bw, bh, bh, bh);
        g.setColor(new Color(12, 14, 18, 220)); g.fill(box); g.setColor(new Color(255, 255, 255, 40)); g.setStroke(new BasicStroke(1.5f)); g.draw(box);
        g.setColor(dot); g.fill(new Ellipse2D.Double(bx0 + 24, boxCY - 8, 16, 16));
        g.setColor(Color.WHITE); g.drawString(text, (float) (bx0 + 52), (float) (boxCY + fm.getAscent() * 0.36));
        g.setComposite(old);
    }

    // ---------------- 자막·상태 ----------------
    static final String[] NAMES = {"정상 주행", "졸음 주의", "졸음 경고", "졸음 위험"};
    static final String[] TILE = {"공조", "시트 통풍", "핸들 열선", "무드등"};
    static final String[] OFFV = {"내기 · 3단", "꺼짐", "꺼짐", "기본색"};
    static final String[] ONV = {"외기 · 7단", "3단", "3단", "경고색"};

    static String caption(double ts, boolean cut) {
        int st = stageAt(ts);
        if (st == 0) return "차량 신호로 졸음 위험을 살피는 중";
        if (st == 1) return "음성으로 휴식을 권하고 외기 전환을 제안해요";
        if (st == 2 && stimAt(ts) < 0.5) return "화면 가장자리 빛과 카드로 먼저 알려요";
        if (st == 2) return cut ? "핸들은 따뜻하게, 바람은 차갑게 몸을 깨워요" : "실내 장치가 직접 깨워요";
        return "가장 가까운 휴게소를 안내하고 정차를 권해요";
    }

    static void overlays(Graphics2D g, double ts, boolean cut) {
        int st = stageAt(ts); Color tc = toneAt(ts);
        int hours = ts < T_S2 ? 1 : ts < T_S3 ? 2 : 3;
        double chipRight = chip(g, 44, 36, "새벽 3:00 · 연속 주행 " + hours + "시간");
        voiceBubble(g, ts, chipRight + 24, tc);
        RoundRectangle2D panel = new RoundRectangle2D.Double(40, 950, W - 80, 104, 30, 30);
        g.setColor(new Color(10, 12, 16, 214)); g.fill(panel); g.setColor(new Color(255, 255, 255, 30)); g.setStroke(new BasicStroke(1.5f)); g.draw(panel);
        g.setFont(bold(32f)); FontMetrics fm = g.getFontMetrics(); String nm = NAMES[st]; int pw = fm.stringWidth(nm) + 60;
        RoundRectangle2D pill = new RoundRectangle2D.Double(68, 974, pw, 56, 56, 56);
        g.setColor(tc); g.fill(pill); g.setColor(INK); g.drawString(nm, 68 + 30, 974 + 28 + fm.getAscent() * 0.36f);
        g.setFont(med(32f)); FontMetrics fc = g.getFontMetrics(); g.setColor(new Color(240, 242, 245)); g.drawString(caption(ts, cut), 68 + pw + 28, 974 + 28 + fc.getAscent() * 0.36f);
        int tw = 196, gap = 10, tx = W - 64 - (tw * 4 + gap * 3);
        for (int i = 0; i < 4; i++) {
            double k = smooth((ts - T_STIM - 0.15 * i) / 0.5);
            double x = tx + i * (tw + gap), y = 964; RoundRectangle2D tile = new RoundRectangle2D.Double(x, y, tw, 76, 22, 22);
            Color acc = st >= 3 ? C_DANGER : C_WARN;
            g.setColor(mix(new Color(26, 28, 33), mix(new Color(26, 28, 33), acc, 0.30), k)); g.fill(tile);
            g.setColor(alpha(mix(new Color(90, 94, 102), acc, k), 0.55 + 0.45 * k)); g.setStroke(new BasicStroke(2f)); g.draw(tile);
            icon(g, i, x + 16, y + 21, 34, k > 0.5 ? mix(Color.WHITE, acc, 0.35) : alpha(Color.WHITE, 0.45 + 0.55 * k));
            g.setFont(med(21f)); g.setColor(alpha(new Color(200, 204, 212), 0.6 + 0.4 * k)); g.drawString(TILE[i], (float) (x + 62), (float) (y + 31));
            g.setFont(bold(26f)); g.setColor(alpha(Color.WHITE, 0.5 + 0.5 * k)); g.drawString(k > 0.5 ? ONV[i] : OFFV[i], (float) (x + 62), (float) (y + 62));
        }
    }

    /** 차량 음성 안내·경고음 자막(위쪽 가운데). 실제 소리와 같은 시각에 보인다. */
    static void voiceBubble(Graphics2D g, double ts, double minX, Color accent) {
        for (Voice v : VOICES) {
            boolean beep = v.kind().equals("B");
            double end = v.ts() + v.dur() + (beep ? 0.25 : 0.4);
            double k = smooth((ts - v.ts() + 0.1) / 0.25) * (1 - smooth((ts - end) / 0.3));
            if (k <= 0.01) continue;
            String text = beep ? v.text() : "“" + v.text() + "”";
            g.setFont(semi(29f)); FontMetrics fm = g.getFontMetrics();
            double maxText = Math.min(1100, 1876 - minX - 108);
            List<String> lines = wrap(text, fm, maxText);
            double tw = 0; for (String l : lines) tw = Math.max(tw, fm.stringWidth(l));
            double lh = 40, bw = 80 + tw + 30, bh = lines.size() * lh + 26;
            double x = Math.max(minX, 960 - bw / 2), y = 30;
            if (x + bw > 1876) x = 1876 - bw;
            Composite old = g.getComposite(); g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) k));
            RoundRectangle2D box = new RoundRectangle2D.Double(x, y, bw, bh, Math.min(bh, 66), Math.min(bh, 66));
            g.setColor(new Color(8, 10, 14, 208)); g.fill(box); g.setColor(new Color(255, 255, 255, 38)); g.setStroke(new BasicStroke(1.5f)); g.draw(box);
            double cy = y + 13 + lh / 2;
            g.setColor(accent); g.fill(new Ellipse2D.Double(x + 20, cy - 20, 40, 40));
            Graphics2D ic = (Graphics2D) g.create(); ic.translate(x + 28, cy - 12); ic.setColor(INK);
            if (beep) {
                Path2D bell = new Path2D.Double(); bell.moveTo(4, 17); bell.lineTo(20, 17); bell.lineTo(18, 14.5); bell.lineTo(18, 10.5);
                bell.curveTo(18, 6.8, 15.4, 4.5, 12, 4.5); bell.curveTo(8.6, 4.5, 6, 6.8, 6, 10.5); bell.lineTo(6, 14.5); bell.closePath();
                ic.fill(bell); ic.fill(new Ellipse2D.Double(9.8, 17.6, 4.4, 3.4));
            } else {
                Path2D spk = new Path2D.Double(); spk.moveTo(3, 9); spk.lineTo(7, 9); spk.lineTo(12, 4.5); spk.lineTo(12, 19.5); spk.lineTo(7, 15); spk.lineTo(3, 15); spk.closePath(); ic.fill(spk);
                ic.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                ic.draw(new Arc2D.Double(10.5, 8, 6, 8, -60, 120, Arc2D.OPEN)); ic.draw(new Arc2D.Double(10, 4.5, 10.5, 15, -60, 120, Arc2D.OPEN));
            }
            ic.dispose();
            g.setColor(Color.WHITE);
            for (int i = 0; i < lines.size(); i++) g.drawString(lines.get(i), (float) (x + 80), (float) (y + 13 + i * lh + lh / 2 + fm.getAscent() * 0.36));
            g.setComposite(old);
        }
    }

    static List<String> wrap(String text, FontMetrics fm, double maxW) {
        List<String> out = new ArrayList<>(); StringBuilder cur = new StringBuilder();
        for (String w : text.split(" ")) {
            String cand = cur.length() == 0 ? w : cur + " " + w;
            if (fm.stringWidth(cand) <= maxW || cur.length() == 0) { cur = new StringBuilder(cand); }
            else { out.add(cur.toString()); cur = new StringBuilder(w); }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    static double chip(Graphics2D g, double x, double y, String text) {
        g.setFont(bold(28f)); FontMetrics fm = g.getFontMetrics(); double w = fm.stringWidth(text) + 56, h = 56;
        RoundRectangle2D r = new RoundRectangle2D.Double(x, y, w, h, h, h);
        g.setColor(new Color(8, 10, 14, 170)); g.fill(r); g.setColor(new Color(255, 255, 255, 36)); g.setStroke(new BasicStroke(1.5f)); g.draw(r);
        g.setColor(Color.WHITE); g.drawString(text, (float) (x + 28), (float) (y + h / 2 + fm.getAscent() * 0.36));
        return x + w;
    }

    static void icon(Graphics2D g0, int kind, double x, double y, double s, Color c) {
        Graphics2D h = (Graphics2D) g0.create(); h.translate(x, y); h.scale(s / 24.0, s / 24.0); h.setColor(c);
        h.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (kind) {
            case 0 -> { for (int i = 0; i < 3; i++) { double yy = 6 + i * 6; Path2D p = new Path2D.Double(); p.moveTo(2, yy); p.curveTo(7, yy - 3, 11, yy + 3, 16, yy); p.curveTo(18, yy - 1.4, 20, yy - 1.4, 22, yy); h.draw(p); } }
            case 1 -> {
                Path2D back = new Path2D.Double(); back.moveTo(5, 3.5); back.lineTo(8.5, 3.5); back.lineTo(10, 15); back.lineTo(6.5, 15); back.closePath(); h.fill(back);
                h.fill(new RoundRectangle2D.Double(5.5, 14, 10.5, 3.6, 3, 3)); h.fill(new Rectangle2D.Double(8, 17.4, 2.4, 3.6));
                for (int i = 0; i < 3; i++) { double yy = 5 + i * 3.6; Path2D p = new Path2D.Double(); p.moveTo(13, yy); p.curveTo(15, yy - 1.4, 17, yy + 1.4, 19, yy); p.curveTo(20, yy - 0.7, 21, yy - 0.7, 22, yy); h.draw(p); }
            }
            case 2 -> { h.draw(new Ellipse2D.Double(3, 3, 18, 18)); h.fill(new Ellipse2D.Double(9, 9, 6, 6)); h.draw(new Line2D.Double(3.8, 11.2, 9, 12)); h.draw(new Line2D.Double(20.2, 11.2, 15, 12)); h.draw(new Line2D.Double(12, 15, 12, 20.4)); }
            default -> { h.fill(new RoundRectangle2D.Double(3, 16, 18, 3.6, 3.6, 3.6)); h.draw(new Line2D.Double(12, 5.5, 12, 11.5)); h.draw(new Line2D.Double(5.5, 8.2, 8, 12)); h.draw(new Line2D.Double(18.5, 8.2, 16, 12)); }
        }
        h.dispose();
    }

    static void backdrop(Graphics2D g, Color glow, double k) {
        g.setPaint(new GradientPaint(0, 0, new Color(7, 9, 13), 0, H, new Color(14, 17, 24))); g.fillRect(0, 0, W, H);
        RadialGradientPaint rg = new RadialGradientPaint(new Point2D.Double(W / 2.0, H * 1.05), W * 0.55f, new float[]{0f, 1f}, new Color[]{alpha(glow, 0.32 * k), alpha(glow, 0)});
        g.setPaint(rg); g.fillRect(0, 0, W, H);
    }
    static void centered(Graphics2D g, String s, Font f, Color c, double y) { g.setFont(f); FontMetrics fm = g.getFontMetrics(); g.setColor(c); g.drawString(s, (float) ((W - fm.stringWidth(s)) / 2.0), (float) y); }

    static void title(Graphics2D g, double t) {
        double k = smooth(t / 0.8);
        backdrop(g, C_DANGER, k);
        Composite old = g.getComposite(); g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) k));
        centered(g, "깨워드림", bold(132f), Color.WHITE, 470);
        centered(g, "WakeMate", semi(46f), new Color(160, 166, 178), 548);
        centered(g, "차량 신호로 졸음 위험을 추정하고, 실내 장치로 직접 깨웁니다", med(40f), new Color(225, 228, 234), 660);
        g.setComposite(old);
    }

    static void end(Graphics2D g, double t, double dur) {
        double out = 1 - smooth((t - (dur - 0.7)) / 0.7);
        backdrop(g, C_WARN, out);
        Composite old = g.getComposite(); g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) clamp01(out)));
        centered(g, "단계마다 다르게 깨웁니다", bold(76f), Color.WHITE, 390);
        String[][] rows = {{"주의", "음성 안내 · 외기 전환 제안"}, {"경고", "공조 · 시트 통풍 · 핸들 열선 · 무드등"}, {"위험", "가까운 졸음쉼터 · 휴게소 안내"}};
        Color[] cs = {C_CAUTION, C_WARN, C_DANGER};
        for (int i = 0; i < 3; i++) {
            double y = 500 + i * 92; g.setFont(bold(34f)); FontMetrics fm = g.getFontMetrics();
            double px = 560; RoundRectangle2D pill = new RoundRectangle2D.Double(px, y - 40, 120, 58, 58, 58);
            g.setColor(cs[i]); g.fill(pill); g.setColor(INK); g.drawString(rows[i][0], (float) (px + (120 - fm.stringWidth(rows[i][0])) / 2.0), (float) (y + 1));
            g.setFont(med(38f)); g.setColor(new Color(235, 237, 241)); g.drawString(rows[i][1], (float) (px + 150), (float) (y + 2));
        }
        centered(g, "앱 화면은 에뮬레이터 녹화 · 실내 장면은 합성 연출 · 음성은 앱 안내 문구를 AI 음성 합성(Inworld TTS)으로 재현", med(26f), new Color(140, 146, 158), 930);
        g.setComposite(old);
    }
}


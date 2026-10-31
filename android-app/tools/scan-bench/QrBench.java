/*
 * 扫码解码的离线基准（§10.20 的全部数字来源）。
 *
 * ## 为什么它是一个不参与构建的文件
 * 它量的是 **ZXing 本身**在同一份像素上的耗时，与 Android、CameraX 都无关；
 * 因此不需要真机、不需要 gradle、不需要联网，一个 JVM 就能跑 —— 这样才能在
 * 「现场反馈说扫得慢」的时候，**先把解码那一环单独测干净**，再决定要不要去动相机侧。
 *
 * 它复刻的是应用里那条真实的解码通路（与 zxing-lite 的
 * `BarcodeFormatAnalyzer.decode` 逐步对应）：
 *
 *     裁剪出亮度面 → HybridBinarizer → BinaryBitmap → reader.decode(bitmap, hints)
 *
 * ## 怎么跑
 * zxing:core 是现成的纯 Java 包，在本机 Gradle 缓存里就有（版本与
 * `app/build.gradle.kts` 的 `com.google.zxing:core` 一致）：
 *
 *     CORE=~/.gradle/caches/modules-2/files-2.1/com.google.zxing/core/3.5.3/<hash>/core-3.5.3.jar
 *     mkdir -p merged && (cd merged && unzip -oq "$CORE")
 *     javac -encoding UTF-8 -cp merged QrBench.java && cp QrBench.class merged/
 *     java -cp merged QrBench
 *
 * （把类解到一个目录里再 `-cp merged`，是为了绕开 Windows 上 `;` 与 POSIX `:` 的
 *   类路径分隔符差异 —— 单条目类路径两边都认。）
 *
 * ## 结果怎么读
 * 关键的一列是「没解出（现场大多数帧）」：对着班牌取景时，绝大多数帧都是
 * 「还没对准 / 还没对焦上」的**失败帧**，因此这一列才决定实际帧率。
 */
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Reader;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.EncodeHintType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class QrBench {

    /** 真机记录里的那条二维码内容（docs/sign-records.txt）。 */
    static final String PAYLOAD =
        "http://124.16.75.106:8081/app/course/stu_scan_sign.action"
      + "?timeTableId=79B6E9E491A34BCD9121FDDDCA91F9A0&timestamp=1791973800896";

    /** 分析裁剪区：1080p 分析 + 库默认 areaRectRatio=0.8 → 0.8 × min(1920,1080) = 864。 */
    static final int SIZE = 864;

    public static void main(String[] args) throws Exception {
        byte[] nearQr = frameWithQr(6);      // 码占约 342px（离班牌较近）
        byte[] farQr = frameWithQr(3);       // 码占约 171px（坐在座位上）
        byte[] noQr = frameWithoutQr();      // 画面里根本没有码

        System.out.println("裁剪区 " + SIZE + "×" + SIZE + " 亮度像素（与 1080p 分析下库的默认裁剪一致）");
        System.out.println();

        Map<DecodeHintType, Object> emptyHints = new EnumMap<>(DecodeHintType.class);

        Map<DecodeHintType, Object> qrHints = new EnumMap<>(DecodeHintType.class);
        qrHints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        qrHints.put(DecodeHintType.CHARACTER_SET, "UTF-8");

        Map<DecodeHintType, Object> allFormatsHints = new EnumMap<>(DecodeHintType.class);
        allFormatsHints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(
            BarcodeFormat.AZTEC, BarcodeFormat.CODABAR, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93,
            BarcodeFormat.CODE_128, BarcodeFormat.DATA_MATRIX, BarcodeFormat.EAN_8, BarcodeFormat.EAN_13,
            BarcodeFormat.ITF, BarcodeFormat.MAXICODE, BarcodeFormat.PDF_417, BarcodeFormat.QR_CODE,
            BarcodeFormat.RSS_14, BarcodeFormat.RSS_EXPANDED, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
            BarcodeFormat.UPC_EAN_EXTENSION));

        System.out.printf("%-44s %-20s %-10s %s%n", "配置（reader + hints）", "画面", "平均毫秒", "结果");
        System.out.println("----------------------------------------------------------------------------------------");

        bench("MultiFormatReader 空 hints  [1.3.4 的行为]", emptyHints, nearQr, "码 342px 居中");
        bench("MultiFormatReader 空 hints  [1.3.4 的行为]", emptyHints, farQr, "码 171px 居中");
        bench("MultiFormatReader 空 hints  [1.3.4 的行为]", emptyHints, noQr, "画面里没有码");

        bench("MultiFormatReader 只认 QR   [1.3.5 的默认]", qrHints, nearQr, "码 342px 居中");
        bench("MultiFormatReader 只认 QR   [1.3.5 的默认]", qrHints, farQr, "码 171px 居中");
        bench("MultiFormatReader 只认 QR   [1.3.5 的默认]", qrHints, noQr, "画面里没有码");

        // 兜底路径（连续 30 帧认不出二维码之后走这条）：与 1.3.4 逐字一致，因此不会更差。
        bench("MultiFormatReader 17 种码制 [1.3.5 的兜底]", allFormatsHints, noQr, "画面里没有码");

        bench("QRCodeReader 无 hints", null, nearQr, "码 342px 居中");
        bench("QRCodeReader 无 hints", null, farQr, "码 171px 居中");
        bench("QRCodeReader 无 hints", null, noQr, "画面里没有码");
    }

    static void bench(String label, Map<DecodeHintType, Object> hints, byte[] frame, String frameLabel) {
        Reader reader = label.startsWith("QRCodeReader") ? new QRCodeReader() : new com.google.zxing.MultiFormatReader();
        LuminanceSource source = new PlanarYUVLuminanceSource(frame, SIZE, SIZE, 0, 0, SIZE, SIZE, false);

        for (int i = 0; i < 40; i++) decodeOnce(reader, hints, source);   // 让 JIT 先热起来

        int n = 60;
        long best = Long.MAX_VALUE;
        long total = 0;
        String result = "";
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            Result r = decodeOnce(reader, hints, source);
            long dt = System.nanoTime() - t0;
            total += dt;
            if (dt < best) best = dt;
            result = r == null ? "未解出" : "解出 " + r.getBarcodeFormat();
        }
        System.out.printf("%-44s %-20s %-10s %s%n", label, frameLabel,
            String.format("%.1f", total / (double) n / 1_000_000.0), result);
        System.out.printf("%-44s %-20s %-10s %s%n", "", "", "(最快 "
            + String.format("%.1f", best / 1_000_000.0) + ")", "");
    }

    static Result decodeOnce(Reader reader, Map<DecodeHintType, Object> hints, LuminanceSource source) {
        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            return hints == null ? reader.decode(bitmap) : reader.decode(bitmap, hints);
        } catch (NotFoundException | ChecksumException | FormatException e) {
            return null;
        }
    }

    /** 把那条真实 payload 画成二维码，并按 `moduleSize` 放大到画面里。 */
    static byte[] frameWithQr(int moduleSize) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);
        BitMatrix m = new QRCodeWriter().encode(PAYLOAD, BarcodeFormat.QR_CODE, 0, 0, hints);

        byte[] y = new byte[SIZE * SIZE];
        Arrays.fill(y, (byte) 0xE0);                       // 码后面是发亮的墙面
        int qrW = m.getWidth() * moduleSize;
        int left = (SIZE - qrW) / 2;
        int top = (SIZE - qrW) / 2;
        for (int my = 0; my < m.getHeight(); my++) {
            for (int mx = 0; mx < m.getWidth(); mx++) {
                int v = m.get(mx, my) ? 0x18 : 0xF0;
                for (int dy = 0; dy < moduleSize; dy++) {
                    int py = top + my * moduleSize + dy;
                    if (py < 0 || py >= SIZE) continue;
                    for (int dx = 0; dx < moduleSize; dx++) {
                        int px = left + mx * moduleSize + dx;
                        if (px < 0 || px >= SIZE) continue;
                        y[py * SIZE + px] = (byte) v;
                    }
                }
            }
        }
        return y;
    }

    /** 没有码的画面：渐变 + 条纹，模拟「对着教室但还没对准班牌」的那类帧。 */
    static byte[] frameWithoutQr() {
        byte[] y = new byte[SIZE * SIZE];
        for (int py = 0; py < SIZE; py++) {
            for (int px = 0; px < SIZE; px++) {
                int v = 0xB0 + (px * 40 / SIZE) - (py * 30 / SIZE);
                if ((px / 7 + py / 11) % 3 == 0) v -= 25;
                y[py * SIZE + px] = (byte) Math.max(0, Math.min(255, v));
            }
        }
        return y;
    }
}

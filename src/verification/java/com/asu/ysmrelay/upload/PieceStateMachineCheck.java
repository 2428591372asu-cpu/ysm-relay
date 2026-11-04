package com.asu.ysmrelay.upload;

import com.asu.ysmrelay.net.msg.UploadChunk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * {@link ServerUpload} 分片状态机的零依赖自检。
 *
 * <p>为什么是 {@code main()} 而不是 JUnit：本机 gradle 缓存里只有 {@code junit-bom}，
 * {@code junit-jupiter} / {@code junit-platform-*} / {@code opentest4j} / {@code apiguardian}
 * 一个都没有，引一个 {@code @Test} 就要去 mavenCentral 拉一整串传递依赖。这十条断言
 * 一个 JUnit 特性都用不到，写成 {@code if} + {@code fail} 与写成 {@code @Test} 信息量相同；
 * 以后网络好了，断言可以原样搬进 {@code @Test}。
 *
 * <p>为什么选这个类：{@code ServerUpload} 是全工程唯一零 MC、零 YSM import 的类，却承载
 * 分包顺序校验、重放拦截、超量拒收、空包拒收与封口条件 —— 最细的路径、最少的运行期验证。
 * 这里不需要 Forge、不需要 native、不需要联网。
 *
 * <p>跑法：{@code gradlew verification --offline}。全过退出码 0；任一失败退出码 1 并打印用例名。
 */
public final class PieceStateMachineCheck {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final long TRANSFER_ID = 7L;
    private static final byte[] DECLARED_ID = {1, 2, 3, 4};

    private static final List<String> FAILURES = new ArrayList<>();
    private static int caseCount;

    private PieceStateMachineCheck() {}

    public static void main(String[] args) throws Exception {
        var workspace = Files.createTempDirectory("ysm-relay-verify");
        try {
            inOrderPiecesSealAndMatch(workspace);
            outOfOrderPieceRejected(workspace);
            replayedPieceRejected(workspace);
            overrunRejected(workspace);
            emptyPieceRejected(workspace);
            writeAfterSealRejected(workspace);
            lastWithoutEnoughBytesIsNotComplete(workspace);
            enoughBytesWithoutLastIsNotComplete(workspace);
            discardRemovesStagingFile(workspace);
        } finally {
            deleteTree(workspace);
        }
        report();
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    /** 正常路径：两片顺序到达、第二片带 last、字节数正好 → 封口且完成，磁盘内容与发出去的一致。 */
    private static void inOrderPiecesSealAndMatch(Path dir) throws IOException {
        var upload = open(dir, "in-order.part", 6);
        try {
            upload.append(piece(0, false, "abc"));
            upload.append(piece(1, true, "def"));
            check("in-order: sealed", upload.isSealed());
            check("in-order: complete", upload.isComplete());
            check("in-order: received=6", upload.received() == 6, "received=" + upload.received());
            var written = Files.readString(upload.stagingFile, StandardCharsets.UTF_8);
            check("in-order: file content", "abcdef".equals(written), "content=" + written);
        } finally {
            upload.close();
        }
    }

    /** 乱序：第一片就发 seq=1 → 拒收，且不推进状态。 */
    private static void outOfOrderPieceRejected(Path dir) throws IOException {
        var upload = open(dir, "out-of-order.part", 6);
        try {
            expectRejected("out-of-order: seq jump", () -> upload.append(piece(1, false, "abc")));
            check("out-of-order: received stays 0", upload.received() == 0, "received=" + upload.received());
            check("out-of-order: not sealed", !upload.isSealed());
        } finally {
            upload.close();
        }
    }

    /** 重放：同一片发两次 → 第二次被拒（sequence 已推进到 1）。 */
    private static void replayedPieceRejected(Path dir) throws IOException {
        var upload = open(dir, "replay.part", 6);
        try {
            upload.append(piece(0, false, "abc"));
            expectRejected("replay: same seq twice", () -> upload.append(piece(0, false, "abc")));
            check("replay: received stays 3", upload.received() == 3, "received=" + upload.received());
        } finally {
            upload.close();
        }
    }

    /** 超量：声明 5 字节，收到 4+4 → 第二片超量被拒，文件里只有前 4 字节。 */
    private static void overrunRejected(Path dir) throws IOException {
        var upload = open(dir, "overrun.part", 5);
        try {
            upload.append(piece(0, false, "abcd"));
            expectRejected("overrun: exceeds declared", () -> upload.append(piece(1, false, "efgh")));
            check("overrun: received stays 4", upload.received() == 4, "received=" + upload.received());
        } finally {
            upload.close();
        }
    }

    /** 空包：data 长度为 0 → 拒收（否则 seq 会被空包推进，字节数却不动）。 */
    private static void emptyPieceRejected(Path dir) throws IOException {
        var upload = open(dir, "empty.part", 6);
        try {
            expectRejected("empty: zero-length data", () -> upload.append(piece(0, false, "")));
            check("empty: received stays 0", upload.received() == 0, "received=" + upload.received());
        } finally {
            upload.close();
        }
    }

    /** 封口之后再写 → 拒收，不会把字节追加到已关闭的文件上。 */
    private static void writeAfterSealRejected(Path dir) throws IOException {
        var upload = open(dir, "after-seal.part", 3);
        try {
            upload.append(piece(0, true, "abc"));
            check("after-seal: sealed", upload.isSealed());
            expectRejected("after-seal: extra piece", () -> upload.append(piece(1, false, "x")));
        } finally {
            upload.close();
        }
    }

    /**
     * 关键用例：收到 last 但字节数不足 → 已封口、但**不算完成**。
     *
     * <p>这条正是「只看 last 会把少传的包当成完整文件」那个缺陷的守门人。
     */
    private static void lastWithoutEnoughBytesIsNotComplete(Path dir) throws IOException {
        var upload = open(dir, "short.part", 10);
        try {
            upload.append(piece(0, true, "abcd"));
            check("short-last: sealed", upload.isSealed());
            check("short-last: NOT complete", !upload.isComplete());
            check("short-last: received=4", upload.received() == 4, "received=" + upload.received());
        } finally {
            upload.close();
        }
    }

    /** 反向：字节数已经够了但没收到 last → 不算完成（两个条件缺一不可）。 */
    private static void enoughBytesWithoutLastIsNotComplete(Path dir) throws IOException {
        var upload = open(dir, "no-last.part", 3);
        try {
            upload.append(piece(0, false, "abc"));
            check("no-last: not sealed", !upload.isSealed());
            check("no-last: NOT complete", !upload.isComplete());
        } finally {
            upload.close();
        }
    }

    /** 放弃上传：暂存文件必须消失（否则只能等 TTL 扫，服务端重启后会留一批垃圾）。 */
    private static void discardRemovesStagingFile(Path dir) throws IOException {
        var upload = open(dir, "discard.part", 6);
        upload.append(piece(0, false, "abc"));
        check("discard: file exists before", Files.exists(upload.stagingFile));
        upload.discard();
        check("discard: file removed", !Files.exists(upload.stagingFile));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static ServerUpload open(Path dir, String fileName, long expectedSize) throws IOException {
        var upload = new ServerUpload(PLAYER, TRANSFER_ID, DECLARED_ID, "model.ysm", dir.resolve(fileName),
            expectedSize);
        upload.open();
        return upload;
    }

    private static UploadChunk piece(int seq, boolean last, String text) {
        return new UploadChunk(TRANSFER_ID, seq, last, text.getBytes(StandardCharsets.UTF_8));
    }

    private interface Body {
        void run() throws IOException;
    }

    /** 断言这段代码会抛 IOException —— 状态机对非法分片的唯一合法回应方式。 */
    private static void expectRejected(String name, Body body) {
        caseCount++;
        try {
            body.run();
        } catch (IOException expected) {
            System.out.println("  ok   " + name + "  (" + expected.getMessage() + ")");
            return;
        }
        fail(name + "：期望抛 IOException，实际没有抛");
    }

    private static void check(String name, boolean condition) {
        check(name, condition, null);
    }

    private static void check(String name, boolean condition, String detail) {
        caseCount++;
        if (condition) {
            System.out.println("  ok   " + name);
        } else {
            fail(name + (detail == null ? "" : "  [" + detail + "]"));
        }
    }

    private static void fail(String message) {
        FAILURES.add(message);
        System.out.println("  FAIL " + message);
    }

    private static void report() {
        System.out.println();
        // 输出刻意全用 ASCII：Windows 控制台默认 GBK，UTF-8 中文在 CI / 重定向里会变乱码。
        System.out.println("cases=" + caseCount + " failures=" + FAILURES.size());
        if (FAILURES.isEmpty()) {
            System.out.println("PieceStateMachineCheck: PASS");
            return;
        }
        for (var failure : FAILURES) {
            System.out.println("  - " + failure);
        }
        System.out.println("PieceStateMachineCheck: FAIL");
        System.exit(1);
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清不掉不影响结论
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}

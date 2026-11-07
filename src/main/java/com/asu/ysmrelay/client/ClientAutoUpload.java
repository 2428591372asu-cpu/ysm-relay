package com.asu.ysmrelay.client;

import com.asu.ysmrelay.upload.ModelIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 自动上传目录。
 *
 * <p>默认用 {@link WatchService}（Windows 上就是 ReadDirectoryChangesW，内核回调，我们不轮询，
 * 一个目录的注册开销可以忽略）。服务端把 {@code autoUploadScanSeconds} 设成大于 0 时改为轮询，
 * 用于网络盘等监听不可靠的路径；监听注册失败时也自动退回 30 秒轮询。
 *
 * <p>去重按内容标识，不按文件名：上传过的记在 {@code client-seen.txt}，
 * 文件改动后标识变化会被重新上传；原文件留在原地，不移动也不删除。
 */
@Mod.EventBusSubscriber(modid = "ysm_relay", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientAutoUpload {
    private static final Logger LOGGER = LoggerFactory.getLogger("ysm_relay");
    private static final Set<String> EXTENSIONS = Set.of(".mxc", ".ysm", ".zip", ".7z");
    /** 监听失败时的兜底轮询间隔（秒）。 */
    private static final int FALLBACK_POLL_SECONDS = 30;

    private static int tickCounter;
    private static volatile boolean scanning;
    private static volatile boolean welcomeScanned;
    private static volatile long lastScanMillis;

    /** 本会话内的解析缓存：文件大小与修改时间都没变就不重新算标识。 */
    private static final java.util.Map<Path, CachedFile> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedFile(String id, long size, long modified) {
    }

    private static WatchService watchService;
    private static Thread watchThread;
    private static Path watchedFolder;
    private static volatile boolean watchFailed;

    private ClientAutoUpload() {}

    public static Path configFile() {
        return FMLPaths.CONFIGDIR.get().resolve("ysm-relay").resolve("client.properties");
    }

    public static Path seenFile() {
        return FMLPaths.CONFIGDIR.get().resolve("ysm-relay").resolve("client-seen.txt");
    }

    public static Path folder() {
        var properties = load();
        var raw = properties.getProperty("autoUploadFolder", "").trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Path.of(raw);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    public static void setFolder(Path folder) {
        var properties = load();
        properties.setProperty("autoUploadFolder", folder == null ? "" : folder.toAbsolutePath().toString());
        var file = configFile();
        try {
            Files.createDirectories(file.getParent());
            var temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (var out = Files.newOutputStream(temporary)) {
                properties.store(out, "ysm-relay client settings");
            }
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            LOGGER.warn("[ysm-relay] 写入客户端配置失败", failure);
        }
        stopWatch();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        var minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            welcomeScanned = false;
            stopWatch();
            return;
        }
        if (ClientUploads.busy()) {
            return;
        }
        if (ClientSettings.disabledByServer()) {
            stopWatch();
            return;
        }
        var snapshot = ClientSettings.current().orElse(null);
        if (snapshot != null && (!snapshot.allowAutoUpload || snapshot.uploadMode == 0)) {
            // 服务端不允许，或当前是审批模式：不扫目录。
            stopWatch();
            return;
        }
        var folder = folder();
        if (folder == null || !Files.isDirectory(folder)) {
            stopWatch();
            return;
        }
        var pollSeconds = snapshot == null ? 0 : snapshot.autoUploadScanSeconds;
        var first = !welcomeScanned;
        if (pollSeconds > 0 || watchFailed) {
            // 轮询模式
            var interval = Math.max(1, pollSeconds > 0 ? pollSeconds : FALLBACK_POLL_SECONDS);
            if (first || ++tickCounter >= interval * 20) {
                tickCounter = 0;
                welcomeScanned = true;
                scanOnce();
            }
            return;
        }
        // 监听模式：注册一次，之后由 watch 线程驱动
        var startedWatch = ensureWatch(folder);
        // 刚从轮询/停机切回监听时目录里已有的文件不会再触发事件，必须立刻补扫一次。
        if (first || startedWatch) {
            welcomeScanned = true;
            scanOnce();
        }
    }

    /** 返回 true 表示这次真的新建了监听（调用方需要补扫一次）。 */
    private static synchronized boolean ensureWatch(Path folder) {
        if (watchService != null && folder.equals(watchedFolder)) {
            return false;
        }
        stopWatchLocked();
        try {
            var service = FileSystems.getDefault().newWatchService();
            folder.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY);
            watchService = service;
            watchedFolder = folder;
            var thread = new Thread(() -> watchLoop(service), "ysm-relay-watch");
            thread.setDaemon(true);
            watchThread = thread;
            thread.start();
            LOGGER.info("[ysm-relay] 已开始监听自动上传目录 {}", folder);
            return true;
        } catch (IOException failure) {
            LOGGER.warn("[ysm-relay] 无法监听该目录，退回 {} 秒轮询：{}", FALLBACK_POLL_SECONDS, folder, failure);
            watchFailed = true;
            return false;
        }
    }

    private static void watchLoop(WatchService service) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                var key = service.take();
                var touched = false;
                for (var event : key.pollEvents()) {
                    if (event.kind() != StandardWatchEventKinds.OVERFLOW) {
                        touched = true;
                    } else {
                        // 事件队列溢出，只能整目录重扫
                        touched = true;
                    }
                }
                key.reset();
                if (!touched) {
                    continue;
                }
                // 复制中的文件先等一拍，避免读到半截内容
                Thread.sleep(800L);
                scanOnce();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException closed) {
            // 正常关闭
        } catch (RuntimeException failure) {
            LOGGER.warn("[ysm-relay] 目录监听异常，退回轮询", failure);
            watchFailed = true;
        }
    }

    private static synchronized void stopWatch() {
        stopWatchLocked();
    }

    private static void stopWatchLocked() {
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
                // 关闭失败无所谓，线程会自己退出
            }
            watchService = null;
            watchedFolder = null;
        }
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
    }

    private static void scanOnce() {
        if (scanning) {
            return;
        }
        // 监听 + 复制会产生密集事件，最短 1 秒一次，避免同一批文件被扫几十遍
        var now = System.currentTimeMillis();
        if (now - lastScanMillis < 1000L) {
            return;
        }
        lastScanMillis = now;
        var folder = folder();
        if (folder == null || !Files.isDirectory(folder)) {
            return;
        }
        scanning = true;
        var thread = new Thread(() -> {
            try {
                var seen = loadSeen();
                var fresh = new ArrayList<Path>();
                try (Stream<Path> files = Files.list(folder)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (EXTENSIONS.stream().noneMatch(name::endsWith)) {
                            continue;
                        }
                        String id;
                        try {
                            var size = Files.size(file);
                            var modified = Files.getLastModifiedTime(file).toMillis();
                            var cached = CACHE.get(file);
                            if (cached != null && cached.size() == size && cached.modified() == modified) {
                                id = cached.id();
                            } else {
                                id = ModelIdentity.of(file).toString();
                                CACHE.put(file, new CachedFile(id, size, modified));
                            }
                        } catch (IOException | RuntimeException invalid) {
                            continue;
                        }
                        if (seen.contains(id)) {
                            continue;
                        }
                        seen.add(id);
                        fresh.add(file);
                    }
                }
                saveSeen(seen);
                if (!fresh.isEmpty()) {
                    Minecraft.getInstance().execute(() -> ClientUploads.enqueue(fresh));
                }
            } catch (IOException failure) {
                LOGGER.warn("[ysm-relay] 扫描自动上传目录失败", failure);
            } finally {
                scanning = false;
            }
        }, "ysm-relay-autoscan");
        thread.setDaemon(true);
        thread.start();
    }

    private static Properties load() {
        var properties = new Properties();
        var file = configFile();
        if (!Files.isRegularFile(file)) {
            return properties;
        }
        try (var in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException ignored) {
            // 读不出来按空配置
        }
        return properties;
    }

    private static Set<String> loadSeen() {
        var result = new LinkedHashSet<String>();
        var file = seenFile();
        if (!Files.isRegularFile(file)) {
            return result;
        }
        try {
            for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                var text = line.trim().toLowerCase(Locale.ROOT);
                if (!text.isEmpty()) {
                    result.add(text);
                }
            }
        } catch (IOException ignored) {
            // 读不出来就当没传过
        }
        return result;
    }

    private static void saveSeen(Set<String> seen) {
        var file = seenFile();
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, List.copyOf(seen), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            LOGGER.warn("[ysm-relay] 写入已上传记录失败", failure);
        }
    }
}

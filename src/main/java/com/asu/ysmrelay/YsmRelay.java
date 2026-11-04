package com.asu.ysmrelay;

import com.asu.ysmrelay.ysm.YsmBridge;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入口。目前只做两件事：判定 YSM 是否可接、在服务端启动后打印目录规模。
 *
 * <p>上传通道、去重、服主管理面分别由后续模块接入，本类不承载业务。
 */
@Mod(YsmRelay.MOD_ID)
public final class YsmRelay {
    public static final String MOD_ID = "ysm_relay";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** YSM 版本是否可接。false 时所有 relay 功能必须静默停用，不能抛异常打断启动。 */
    private static volatile boolean ysmCompatible;
    /** 模型数量日志只打印一次。 */
    private static volatile boolean catalogLogged;

    public YsmRelay() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** YSM 是否可以接入。 */
    public static boolean ysmCompatible() {
        return ysmCompatible;
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        var version = YsmBridge.ysmVersion().orElse(null);
        if (version == null) {
            ysmCompatible = false;
            LOGGER.error("[ysm-relay] 找不到 ysm mod。relay 功能全部停用。");
            return;
        }
        ysmCompatible = YsmBridge.versionOk();
        if (ysmCompatible) {
            LOGGER.info("[ysm-relay] YSM {} 可接入，适配层就绪", version);
        } else {
            LOGGER.error("[ysm-relay] YSM {} 不在受支持范围 3.x，relay 功能全部停用", version);
        }
    }

    @SubscribeEvent
    public void serverStarted(ServerStartedEvent event) {
        if (!ysmCompatible) {
            return;
        }
        reportCatalog("server started");
    }

    /**
     * YSM 在服务端启动后异步重扫目录，ServerStartedEvent 时数量可能还是 0。
     * 因此这里补一次：数量首次大于 0 时再打印一行，之后不再打印。
     */
    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || catalogLogged || !ysmCompatible) {
            return;
        }
        if (!YsmBridge.serverReady()) {
            return;
        }
        if (YsmBridge.serverModelIds().isEmpty()) {
            return;
        }
        reportCatalog("catalog scanned");
    }

    private static void reportCatalog(String reason) {
        if (!YsmBridge.serverReady()) {
            LOGGER.info("[ysm-relay] {}：服务端模型服务尚未建立", reason);
            return;
        }
        var models = YsmBridge.serverModelIds();
        var files = YsmBridge.serverFiles();
        catalogLogged = true;
        LOGGER.info("[ysm-relay] {}：服务端模型 {} 个（其中 {} 个由文件支撑），模型包 {} 个",
            reason, models.size(), files.size(), YsmBridge.serverPacks().size());
    }
}

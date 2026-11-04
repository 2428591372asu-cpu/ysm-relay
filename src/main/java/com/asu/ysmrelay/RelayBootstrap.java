package com.asu.ysmrelay;

import com.asu.ysmrelay.config.RelayConfig;
import com.asu.ysmrelay.net.RelayNetwork;

import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLConstructModEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 通道与配置的注册点。
 *
 * <p>单独成类而不是塞进 {@link YsmRelay}，是为了让「谁负责注册什么」一眼可见：
 * 主类只管 YSM 兼容性判定，这里只管 relay 自己的基础设施。
 *
 * <p>两个时机都不能改：配置必须在 mod 构造期注册，网络通道必须在 common setup 里注册。
 * 放到更晚（比如 server started）通道就发不出包了。
 */
@Mod.EventBusSubscriber(modid = YsmRelay.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RelayBootstrap {
    private RelayBootstrap() {}

    @SubscribeEvent
    public static void onConstruct(FMLConstructModEvent event) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, RelayConfig.SPEC, "ysm-relay-server.toml");
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        // messageBuilder 必须在 setup 阶段同步完成，不能进 enqueueWork。
        RelayNetwork.init();
        YsmRelay.LOGGER.info("[ysm-relay] 上传通道已注册：{} v{}", RelayNetwork.CHANNEL_NAME,
            RelayNetwork.PROTOCOL_VERSION);
    }
}

package com.asu.ysmrelay.client;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 客户端命令入口，M4 的图形面板做好之前的临时通道。
 *
 * <p>用 {@code @Mod.EventBusSubscriber} 自动挂载，不需要改 mod 主类。
 */
@Mod.EventBusSubscriber(modid = "ysm_relay", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientCommands {
    private ClientCommands() {}

    @SubscribeEvent
    public static void onRegister(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ysmrelay")
            .then(Commands.literal("upload")
                .then(Commands.argument("file", StringArgumentType.greedyString())
                    .executes(context -> {
                        var raw = StringArgumentType.getString(context, "file").trim();
                        if (raw.length() > 1 && raw.startsWith("\"") && raw.endsWith("\"")) {
                            raw = raw.substring(1, raw.length() - 1);
                        }
                        Path file;
                        try {
                            file = Path.of(raw);
                        } catch (InvalidPathException invalid) {
                            feedback("路径不合法：" + raw, true);
                            return 0;
                        }
                        var problem = ClientUploads.start(file);
                        if (problem != null) {
                            feedback(problem, true);
                            return 0;
                        }
                        feedback("已开始上传 " + file.getFileName(), false);
                        return 1;
                    })))
            .then(Commands.literal("cancel").executes(context -> {
                if (!ClientUploads.busy()) {
                    feedback("当前没有进行中的上传", true);
                    return 0;
                }
                ClientUploads.cancel();
                return 1;
            }))
            .then(Commands.literal("status").executes(context -> {
                feedback("阶段=" + ClientUploads.phase()
                    + " 进度=" + Math.round(ClientUploads.progress() * 100.0F) + "%"
                    + (ClientUploads.statusLine().isEmpty() ? "" : " " + ClientUploads.statusLine()), false);
                return 1;
            })));
    }

    private static void feedback(String text, boolean error) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        var component = Component.literal("[ysm-relay] " + text);
        player.displayClientMessage(error ? component.copy().withStyle(ChatFormatting.RED) : component, false);
    }
}

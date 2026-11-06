package com.asu.ysmrelay.client;

import com.asu.ysmrelay.net.msg.DataReply;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 客户端手里的两份列表快照，界面打开时请求、收到后重绘。 */
public final class ClientLists {
    private static final Logger LOGGER = LoggerFactory.getLogger("ysm_relay");
    private static volatile List<DataReply.Entry> mine = List.of();
    private static volatile List<DataReply.Entry> pending = List.of();
    private static volatile List<DataReply.Entry> myPending = List.of();

    private ClientLists() {}

    /** 请求一份列表；服务端回包之后 {@link ListsScreen} 会重绘。 */
    public static void request(int kind) {
        com.asu.ysmrelay.net.RelayNetwork.toServer(new com.asu.ysmrelay.net.msg.DataRequest(kind));
        LOGGER.info("[ysm-relay] 列表请求已发出 kind={}", kind);
    }

    public static void accept(DataReply message) {
        if (message.kind == com.asu.ysmrelay.net.msg.DataRequest.KIND_MINE) {
            mine = List.copyOf(message.entries);
        } else if (message.kind == com.asu.ysmrelay.net.msg.DataRequest.KIND_MY_PENDING) {
            myPending = List.copyOf(message.entries);
        } else {
            pending = List.copyOf(message.entries);
        }
        ListsScreen.onData();
    }

    public static List<DataReply.Entry> mine() {
        return mine;
    }

    public static List<DataReply.Entry> pending() {
        return pending;
    }

    public static List<DataReply.Entry> myPending() {
        return myPending;
    }

    public static void clear() {
        mine = List.of();
        pending = List.of();
        myPending = List.of();
    }
}

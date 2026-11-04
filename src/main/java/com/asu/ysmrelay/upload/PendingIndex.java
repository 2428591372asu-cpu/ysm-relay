package com.asu.ysmrelay.upload;

import com.elfmcys.ysm.model.domain.Hash256;

/**
 * 待审索引：探测阶段唯一需要知道的「排队中有没有同一份内容」。
 *
 * <p>存在的理由是那条最难发现的漏洞：待审内容已经落盘、但还没有变成目录里的模型，
 * 于是 {@code catalog} 查不到它、{@code pendingCommits} 也已经清空 —— 同一份模型会被
 * 第二个人完整再传一遍，服主要审两遍，批准其中一个时另一个变成孤儿文件。
 * 所以探测阶段必须再查一处「正在等待审核」。这个接口就是那一处。
 *
 * <h2>谁实现、谁调用</h2>
 * <ul>
 *   <li><b>实现与 install()：W3 的 {@code PendingQueue}，且只有它一个。</b></li>
 *   <li><b>读：探测阶段的 {@link UploadService#onProbe}。</b>只调 {@link #contains} 与 {@link #size}。</li>
 * </ul>
 *
 * <p>W3 落地之前 {@link #current()} 返回 {@link #EMPTY}，探测行为与现在完全一致
 * （永不命中、size 恒为 0），所以这个接口可以先合、不会让上传通道空转。
 *
 * <p><b>不要在探测路径上调 {@link #add} / {@link #remove}</b>：那两个的调用方是 W3 的审核队列。
 * 探测是多玩家并发进来的，索引的写入必须由队列自己串行保证。
 */
public interface PendingIndex {
    /** 空实现：没有审核队列时的默认值。 */
    PendingIndex EMPTY = new PendingIndex() {
        @Override
        public boolean contains(Hash256 modelId) {
            return false;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public void add(Hash256 modelId, String reviewId) {
            // 没有队列，收到什么都不该记
        }

        @Override
        public void remove(Hash256 modelId) {
            // 同上
        }
    };

    /** 这份内容是不是已经在等服主审核。 */
    boolean contains(Hash256 modelId);

    /** 当前排队条数。用于「队列满 → REJECTED_QUEUE_FULL」的判定。 */
    int size();

    /** 入队时由 W3 调用。 */
    void add(Hash256 modelId, String reviewId);

    /** 审核结束（批准 / 拒绝 / 过期）时由 W3 调用。 */
    void remove(Hash256 modelId);

    /** 由 W3 在启动时装一次；传 {@code null} 等于卸回空实现。 */
    static void install(PendingIndex index) {
        Holder.CURRENT = index == null ? EMPTY : index;
    }

    /** 探测阶段读这里。 */
    static PendingIndex current() {
        return Holder.CURRENT;
    }

    /** 接口字段必须是常量，所以可变的当前实例放在这个嵌套类里。 */
    final class Holder {
        private static volatile PendingIndex CURRENT = EMPTY;

        private Holder() {}
    }
}

package io.kestra.relay;

import io.micronaut.runtime.server.event.ServerStartupEvent;
import io.micronaut.http.server.netty.NettyHttpServer;
import io.micronaut.http.server.netty.NettyServerCustomizer;
import io.micronaut.runtime.event.annotation.EventListener;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.nio.NioChannelOption;
import jakarta.inject.Singleton;

import jdk.net.ExtendedSocketOptions;

/**
 * 方案 A（2026-09-27）：TCP keepalive 半开断线检测 —— 连接级强制配置。
 *
 * <p>为什么用代码而不是 yml：Micronaut 5.1.15 的 {@code micronaut.server.netty.child-options}
 * 绑定不可靠 —— 行为级实测（getsockopt 探针）显示 yml 大写枚举名、kebab-case、乃至
 * {@code -Dmicronaut.server.netty.child-options.SO_KEEPALIVE} 系统属性均未应用到已建立
 * 连接（SO_KEEPALIVE=0、TCP_KEEPIDLE=7200 内核默认），即该配置通道对运行中的服务不生效。
 * 另外 Micronaut 5.1.15 的 NettyHttpServer 不会自动收集 NettyServerCustomizer bean（构造器
 * 无 List 注入；StartupEvent 时 NettyHttpServer bean 尚不存在（它由 EmbeddedServer 在
 * context start 之后创建），必须等 {@link ServerStartupEvent}（server 启动完成）后显式
 * {@code register()} —— 之后每个 accept 的连接都会应用 keepalive 选项（register 前无
 * 业务连接，无漏网窗口）。
 *
 * <p><b>方案 A（2026-09-27 定稿，行为级实证通过）</b>：删除应用层心跳和出站 SSE ping 后，
 * SSE 连接在两次手机消息之间是纯静默的。配合 docker-compose sysctl
 * （tcp_keepalive_time=30 / tcp_keepalive_intvl=5 / tcp_keepalive_probes=3 /
 * tcp_retries2=3），内核 ~30-60s 内判死半开连接 → Netty channelInactive →
 * FluxSink dispose → disconnect() → sink 从 map 移除 → 手机下一条 /input 返回
 * delivered:false。回归门：relay-keepalive-test.sh。
 *
 * <p>注意：JVM ExtendedSocketOptions（NioChannelOption.of）在 Micronaut 5.1.15 NIO
 * transport 上 setOption 返回 true 但实测不生效，故实际 keepalive 参数由 docker-compose
 * sysctl 设置；本 customizer 仅确保 SO_KEEPALIVE=on（开关），参数值由 sysctl 提供。
 *
 * <p>只对 relay 服务的连接生效（本 bean 只存在于 relay 模块），不触碰 Kestra 主监听器。
 */
@Singleton
public class RelayKeepAliveCustomizer implements NettyServerCustomizer {
    static final int KEEP_IDLE_SEC = 30;
    static final int KEEP_INTVL_SEC = 5;
    static final int KEEP_CNT = 3;

    @EventListener
    public void onServerStartup(ServerStartupEvent event) {
        // NettyHttpServer 不是 ApplicationContext 的 bean（实测 getBean 抛 NoSuchBeanException），
        // 直接从事件源（EmbeddedServer 即 NettyHttpServer 实例）拿。
        NettyHttpServer server = (NettyHttpServer) event.getSource();
        server.register(this);
    }

    @Override
    public NettyServerCustomizer specializeForChannel(Channel channel, ChannelRole role) {
        if (role == ChannelRole.CONNECTION) {
            // 注意：setOption 返回值必须检查——Netty 对不认识的 ChannelOption 静默返回
            // false（不抛异常），曾导致 KEEPIDLE 实际未生效但无任何报错。
            boolean okSo = channel.config().setOption(ChannelOption.SO_KEEPALIVE, true);
            // TCP_KEEPIDLE/TCP_KEEPINTERVAL/TCP_KEEPCOUNT 不是 Netty ChannelOption 常量，
            // 而是 JDK NIO ExtendedSocketOptions（jdk.net 包；Netty 通过 NioChannelOption.of
            // 映射；仅对 NioSocketChannel 生效，Micronaut 5 默认 transport 为 nio）。
            // 注意 JDK 官方字段名：TCP_KEEPINTERVAL / TCP_KEEPCOUNT（不是 INTVL/CNT）。
            boolean okIdle = channel.config().setOption(NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPIDLE), KEEP_IDLE_SEC);
            boolean okIntvl = channel.config().setOption(NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPINTERVAL), KEEP_INTVL_SEC);
            boolean okCnt = channel.config().setOption(NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPCOUNT), KEEP_CNT);
            if (!(okSo && okIdle && okIntvl && okCnt)) {
                // 静默失败不抛异常——此处强制可见：setOption 返回 false = keepalive 未应用。
                // 若触发请按 relay-keepalive-test.sh 的 AI-guard 检查清单排查
                // （NioChannelOption.of + JDK ExtendedSocketOptions 字段名 + 容器 JDK 支持度）。
                throw new IllegalStateException(
                    "[relay-keepalive] setOption failed: so=" + okSo + " idle=" + okIdle
                        + " intvl=" + okIntvl + " cnt=" + okCnt);
            }
        }
        return this;
    }
}

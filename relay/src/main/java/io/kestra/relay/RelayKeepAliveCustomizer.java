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
 * <p><b>生效边界（2026-09-27 行为级实证，勿再宣称 keepalive 能判 SSE 半开）</b>：
 * relay-keepalive-test.sh 用 iptables 双向 DROP 模拟 PC 断电，实证：
 * <ul>
 *   <li>纯静默连接（无出站流量）：DROP 后 ~45-72s 被内核判死 → keepalive 生效；</li>
 *   <li>SSE 心跳连接（relay 每 15s 出站 heartbeat）：DROP 后 150s 仍不判死 —— 出站
 *       心跳持续刷新内核 keepalive idle 计数，探测永不触发。keepalive 对 SSE 场景无效。</li>
 * </ul>
 * 因此 <b>PC 半开判死由应用层心跳承担</b>（DshRelayController：PC 每 30s POST
 * /heartbeat，90s 超时判离线）；本 customizer 仅作为<b>纯静默连接</b>（无出站流量的
 * 8090 消费者）的僵尸连接兜底，不可作为 SSE 判死依据。
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

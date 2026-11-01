package com.personalos.calendar.push;

/** 网络适配器，便于在不向外部发送通知的情况下验证投递与重试。 */
public interface PushTransport {
    int send(String endpoint, String publicKey, String auth, byte[] payload) throws Exception;
}

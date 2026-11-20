package com.bit.coin.api;

import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.conn.QuicConnection;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.base.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.bit.coin.p2p.conn.QuicConnectionManager.*;
import static com.bit.coin.p2p.protocol.ProtocolEnum.TEXT_V1;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@RestController
@RequestMapping("/connect")
public class ConnectApi {

    @PostMapping("/connect")
    public String connect(String addr) throws Exception {
        QuicConnection quicConnection = connectRemoteByAddr(addr);
        if (quicConnection != null) {
            return "连接成功";
        } else {
            return "连接失败";
        }
    }

    @PostMapping("/disconnect")
    public String disconnect(String peerId) throws Exception {
        if (disConnectRemoteByPeerId(peerId)) {
            return "断开连接成功";
        } else {
            return "断开连接失败";
        }
    }

    @PostMapping("/sendMsg")
    public String sendMsg(String peerId, Integer count) throws Exception {
        byte[] decode = Base58.decode(peerId);
        //节点回复反转换后的数据
        if (count == null || count < 1 || count > 1_000_000) {
            throw new IllegalArgumentException("Message size must be 1..1000000");
        }
        int targetLength = count;
        byte[] mockData = new byte[targetLength]; // 初始化2048字节数组
        // 可选：填充固定字符（比如用 'a' 填充，避免全零数据）
        // 每个字节填充为字符'a'的ASCII码
        Arrays.fill(mockData, (byte) 'a');
        P2PMessage response = sendRequest(bytesToHex(decode), TEXT_V1, mockData, 5000);
        if (response != null) {
            return parseUtf8(response.getData());
        }
        log.info("回复为空");
        return "123";
    }

    public static String parseUtf8(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        try {
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("解析UTF8失败，返回十六进制", e);
            return bytesToHex(bytes);
        }
    }

}

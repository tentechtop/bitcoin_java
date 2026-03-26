package com.bit.coin.p2p.kad;

import com.bit.coin.p2p.peer.Peer;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;


//节点ID是 32字节Hex
@Slf4j
@Data
public class Bucket {
    // 桶ID
    private int id;

    //存储节点ID 保证节点顺序 用LinkedList保证插入修改速度
    private ConcurrentLinkedDeque<String> nodeIds = new ConcurrentLinkedDeque<>();

    //ID与节点信息的映射
    private final ConcurrentHashMap<String, Peer> nodeMap = new ConcurrentHashMap<>();

    //桶最后访问时间
    private long lastSeen;

    public Bucket(int id) {
        this.id = id;
    }

    public int size() {
        return nodeIds.size();
    }

    public boolean contains(Peer peer) {
        return nodeIds.contains(peer.getId());
    }

    public boolean contains(String id) {
        return nodeIds.contains(id);
    }

    /**
     * 推送到头部（线程安全版本）
     * 先移除旧节点，再添加到头部，保证顺序正确性
     */
    public void pushToFront(Peer peer) {
        log.debug("推送到头部{}", peer.toString());
        String peerId = bytesToHex(peer.getId());

        // 加锁保证操作原子性（ConcurrentLinkedDeque本身线程安全，但组合操作需加锁）
        synchronized (this) {
            // 1. 先移除（若存在）
            nodeIds.remove(peerId);
            // 2. 添加到头部
            nodeIds.addFirst(peerId);
            peer.setLastSeen(System.currentTimeMillis());
            //判断是否存在
            if (nodeMap.containsKey(peerId)) {
                //存在就更新之前的即可
                Peer exist = nodeMap.get(peerId);
                exist.setAddress(peer.getAddress());
                exist.setPort(peer.getPort());
                //更新协议版本
                exist.setProtocolVersion(peer.getProtocolVersion());
                //更新节点类型
                exist.setNodeType(peer.getNodeType());
                //latestSlot
                exist.setLatestSlot(peer.getLatestSlot());
                //isValidator
                exist.setValidator(peer.isValidator());
                //stakeAmount
                exist.setStakeAmount(peer.getStakeAmount());
                //softwareVersion
                exist.setSoftwareVersion(peer.getSoftwareVersion());
                //lastSeen
                exist.setLastSeen(peer.getLastSeen());
                //hashHex
                exist.setHashHex(peer.getHashHex());
                log.debug("路由表最新Hash: {}",peer.getHashHex());
                //height
                exist.setHeight(peer.getHeight());
                log.debug("路由表最新高度: {}",peer.getHeight());
                //工作总量
                exist.setChainWork(peer.getChainWork());
                log.debug("工作总量: {}",peer.getChainWork().toString());
            }else {
                nodeMap.put(peerId, peer);
            }
            this.lastSeen = System.currentTimeMillis();
        }
    }

    /**
     * 添加节点到头部（线程安全）
     */
    public void add(Peer peer) {
        String peerId = bytesToHex(peer.getId());
        // 避免重复添加
        if (!nodeIds.contains(peerId)) {
            nodeIds.addFirst(peerId);
            nodeMap.put(peerId, peer);
            this.lastSeen = System.currentTimeMillis();
        }
    }

    public Peer getNode(String id) {
        Peer peer = nodeMap.get(id);
        if (peer != null){
            return peer;
        }
        return null;
    }

    /**
     * 移除节点（线程安全）
     */
    public void remove(Peer peer){
        this.remove(peer.getId());
    }


    public void remove(byte[] peerId) {
        // 先将byte[]转为Hex字符串，再删除
        String peerIdHex = bytesToHex(peerId);
        nodeIds.remove(peerIdHex); // 正确：删除String类型的ID
        nodeMap.remove(peerIdHex); // 正确：删除String类型的key
        this.lastSeen = System.currentTimeMillis();
    }

    /**
     * 将节点添加到链表末尾（表示活跃度较低），若节点已存在则先移除再添加到末尾
     * @param peer 要操作的节点
     */
    public void pushToAfter(Peer peer) {
        String peerId = bytesToHex(peer.getId());
        nodeIds.remove(peerId);
        nodeIds.addLast(peerId); // 线程安全的尾部添加
        peer.setLastSeen(System.currentTimeMillis());
        //判断是否存在
        if (nodeMap.containsKey(peerId)) {
            //存在就更新之前的即可
            Peer exist = nodeMap.get(peerId);
            exist.setAddress(peer.getAddress());
            exist.setPort(peer.getPort());
            //更新协议版本
            exist.setProtocolVersion(peer.getProtocolVersion());
            //更新节点类型
            exist.setNodeType(peer.getNodeType());
            //latestSlot
            exist.setLatestSlot(peer.getLatestSlot());
            //isValidator
            exist.setValidator(peer.isValidator());
            //stakeAmount
            exist.setStakeAmount(peer.getStakeAmount());
            //softwareVersion
            exist.setSoftwareVersion(peer.getSoftwareVersion());
            //lastSeen
            exist.setLastSeen(peer.getLastSeen());
            //hashHex
            exist.setHashHex(peer.getHashHex());
            log.debug("路由表最新Hash: {}",peer.getHashHex());
            //height
            exist.setHeight(peer.getHeight());
            log.debug("路由表最新高度: {}",peer.getHeight());
            //工作总量
            exist.setChainWork(peer.getChainWork());

        }else {
            nodeMap.put(peerId, peer);
        }
        this.lastSeen = System.currentTimeMillis();
    }


    /**
     * 获取所有节点ID（用于遍历，返回不可修改的视图避免并发问题）
     */
    public Iterable<String> getNodeIds() {
        // 返回迭代器的快照，避免遍历中被修改导致异常
        return () -> nodeIds.iterator();
    }

    public Iterable<Peer> getNodes() {
        // 创建一个迭代器，返回迭代器的快照，避免遍历中被修改导致异常
        return () -> nodeMap.values().iterator();
    }

    /**
     * 遍历返回包含所有节点的ArrayList
     * 注：返回的是节点的快照副本，避免后续修改影响原始数据
     */
    public ArrayList<Peer> getNodesArrayList() {
        // 创建新的ArrayList用于存储节点副本
        ArrayList<Peer> nodesList = new ArrayList<>(nodeMap.size());
        // 遍历映射中的所有节点并添加到列表
        nodesList.addAll(nodeMap.values());
        return nodesList;
    }


    //更新节点最后访问时间
    public void updateLastSeen(String peerId) {
        // 加锁保证操作原子性，避免并发修改问题
        synchronized (this) {
            // 1. 检查节点是否存在于当前桶中
            if (nodeMap.containsKey(peerId)) {
                // 2. 获取节点并更新其最后访问时间为当前时间戳
                Peer peer = nodeMap.get(peerId);
                long currentTime = System.currentTimeMillis();
                peer.setLastSeen(currentTime);

                // 3. 更新桶本身的最后访问时间
                this.lastSeen = currentTime;

                log.debug("更新节点[{}]的最后访问时间为: {}ms", peerId, currentTime);
            } else {
                // 节点不存在时打印调试日志，方便排查问题
                log.debug("尝试更新不存在的节点[{}]的最后访问时间，桶ID: {}", peerId, this.id);
            }
        }
    }

    public void updateLastSeen(byte[] peerIdBytes) {
        String peerIdHex = bytesToHex(peerIdBytes);
        this.updateLastSeen(peerIdHex);
    }

}

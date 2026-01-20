package com.bit.coin.p2p.kad;

import com.bit.coin.p2p.peer.Peer;
import lombok.Data;
import org.bitcoinj.core.Base58;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;


//节点ID是 32字节Hex
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
        String peerId = Base58.encode(peer.getId());
        // 1. 先移除（若存在）
        nodeIds.remove(peerId); // ConcurrentLinkedDeque的remove是线程安全的
        // 2. 添加到头部
        nodeIds.addFirst(peerId); // 线程安全的头部添加
        // 3. 更新映射中的节点信息（如最后活跃时间）
        Peer existingPeer = nodeMap.get(peerId);
        if (existingPeer != null) {
            existingPeer.setLastSeen(peer.getLastSeen());
        } else {
            // 若映射中没有，补充添加（避免数据不一致）
            nodeMap.put(peerId, peer);
        }
        // 更新最后访问时间
        this.lastSeen = System.currentTimeMillis();
    }

    /**
     * 添加节点到头部（线程安全）
     */
    public void add(Peer peer) {
        String peerId = Base58.encode(peer.getId());
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
        nodeIds.remove(peerId);
        nodeMap.remove(peerId);
        this.lastSeen = System.currentTimeMillis();
    }

    /**
     * 将节点添加到链表末尾（表示活跃度较低），若节点已存在则先移除再添加到末尾
     * @param peer 要操作的节点
     */
    public void pushToAfter(Peer peer) {
        String peerId = Base58.encode(peer.getId());
        nodeIds.remove(peerId);
        nodeIds.addLast(peerId); // 线程安全的尾部添加
        Peer existingPeer = nodeMap.get(peerId);
        if (existingPeer != null) {
            existingPeer.setLastSeen(peer.getLastSeen());
        } else {
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

}

package com.getjobs.worker.platform.model;

import lombok.Data;

/**
 * 聊天页里的一条会话（"谁回了我"）。
 *
 * <p>平台自己负责"怎么读会话列表"，只把「公司名 + 最新一条消息」交出来；
 * 至于"跟上次比有没有变"由流程层比对快照，因为那是平台无关的逻辑。
 */
@Data
public class ChatReply {

    /** 会话对应的公司名（平台自己的展示口径，用于和黑名单/落库对齐） */
    private String companyName;

    /** 最新一条消息原文 */
    private String lastMessage;

    public ChatReply() {
    }

    public ChatReply(String companyName, String lastMessage) {
        this.companyName = companyName;
        this.lastMessage = lastMessage;
    }
}

package com.demo.session.dto;

import com.demo.session.entity.SessionMessageEntity;

import java.util.List;

/**
 * 会话消息分页结果
 * <p>
 * page=1 为最近一页；list 已按 createTime 升序排列（最早在前），供前端直接顺序渲染。
 */
public class MessagePage {

    /** 会话消息总数 */
    public long total;
    /** 当前页码（1-based） */
    public int page;
    /** 每页条数 */
    public int size;
    /** 当前页消息列表（升序） */
    public List<SessionMessageEntity> list;

    public MessagePage() {}

    public MessagePage(long total, int page, int size, List<SessionMessageEntity> list) {
        this.total = total;
        this.page = page;
        this.size = size;
        this.list = list;
    }
}

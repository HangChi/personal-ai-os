package com.personalos.common.search;

/**
 * 一条搜索命中。
 *
 * <p><b>刻意不带跳转路由</b>：后端不知道前端路由表，两边会各自漂移。
 * 这里给业务概念 {@code kind} + {@code id}，由前端维护 {@code kind → route} 映射 ——
 * 那才是唯一该关心「点了去哪」的一层。
 *
 * @param kind          对象类型（task / session / event / memory / document），前端据此路由
 * @param id            对象标识
 * @param title         主标题
 * @param subtitle      次要信息（时间、状态等），可空
 * @param matchedField  命中的字段（标题 / 描述 / 地点 / 内容 / 消息内容 / 文件名 / 正文）
 */
public record SearchHit(String kind, String id, String title, String subtitle, String matchedField) {
}

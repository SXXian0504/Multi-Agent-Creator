package com.sxxian.multiagentcreator.service;

import com.stripe.model.checkout.Session;
import com.stripe.exception.StripeException;
import com.sxxian.multiagentcreator.model.entity.PaymentRecord;
import com.stripe.model.Event;

import java.util.List;

/**
 * 支付服务
 *
 */
public interface PaymentService {

    /**
     * 创建 VIP 永久会员支付会话
     *
     * @param userId 用户ID
     * @return Stripe Checkout Session URL
     */
    String createVipPaymentSession(Long userId) throws StripeException;

    /**
     * 处理支付成功回调
     *
     * @param session Stripe Checkout Session
     */
    void handlePaymentSuccess(Session session);

    /**
     * 处理退款
     *
     * @param userId 用户ID
     * @param reason 退款原因
     * @return 是否退款成功
     */
    boolean handleRefund(Long userId, String reason) throws StripeException;

    /**
     * 验证 Webhook 签名
     *
     * @param payload 请求体
     * @param sigHeader 签名头
     * @return Stripe Event
     */
    Event constructEvent(String payload, String sigHeader) throws Exception;

    /**
     * 获取用户支付记录
     *
     * @param userId 用户ID
     * @return 支付记录列表
     */
    List<PaymentRecord> getPaymentRecords(Long userId);
}

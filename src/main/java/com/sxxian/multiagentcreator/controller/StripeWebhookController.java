package com.sxxian.multiagentcreator.controller;

import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.sxxian.multiagentcreator.service.PaymentService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhook")
@Slf4j
@Hidden
public class StripeWebhookController {

    @Resource
    private PaymentService paymentService;

    /**
     * 处理 Stripe Webhook 回调
     */
    @PostMapping("/stripe")
    public String handleStripeWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {
        
        try {
            // 验证 Webhook 签名
            Event event = paymentService.constructEvent(payload, sigHeader);
            
            log.info("收到 Stripe Webhook 事件, type={}", event.getType());
            
            // 处理事件
            switch (event.getType()) {
                case "checkout.session.completed":
                    // 支付成功
                    Session session = (Session) event.getDataObjectDeserializer()
                            .getObject()
                            .orElseThrow(() -> new RuntimeException("无法解析 Session 对象"));
                    paymentService.handlePaymentSuccess(session);
                    break;
                    
                case "checkout.session.async_payment_succeeded":
                    // 异步支付成功
                    Session asyncSession = (Session) event.getDataObjectDeserializer()
                            .getObject()
                            .orElseThrow(() -> new RuntimeException("无法解析 Session 对象"));
                    paymentService.handlePaymentSuccess(asyncSession);
                    break;
                    
                default:
                    log.info("未处理的事件类型: {}", event.getType());
                    break;
            }
            
            return "success";
        } catch (Exception e) {
            log.error("处理 Stripe Webhook 失败", e);
            return "error";
        }
    }
}

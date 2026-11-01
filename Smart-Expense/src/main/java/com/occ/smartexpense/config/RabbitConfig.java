package com.occ.smartexpense.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RabbitMQ 拓扑声明（启动时由 RabbitAdmin 幂等创建），拓扑见 docs/07-Outbox与消息一致性.md §7。
 *
 * <pre>
 * se.report.exchange --report.approved--> se.payment.queue
 *                                              │ 消费失败（不可重试）
 *                                              ▼
 *                     se.dlx.exchange --payment.dead--> se.payment.dlq
 * </pre>
 */
@Configuration
public class RabbitConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);

    public static final String REPORT_EXCHANGE = "se.report.exchange";
    public static final String REPORT_APPROVED_QUEUE = "se.payment.queue";
    public static final String REPORT_APPROVED_ROUTING_KEY = "report.approved";

    /** AI 审批 Copilot：提交事件 → 异步跑 Agent（docs/11 §5.2） */
    public static final String REPORT_SUBMITTED_ROUTING_KEY = "report.submitted";
    public static final String AI_REVIEW_QUEUE = "se.ai-review.queue";
    public static final String AI_DLQ_QUEUE = "se.ai-review.dlq";
    public static final String AI_DLQ_ROUTING_KEY = "ai-review.dead";

    /** 发票识别：上传事件 → 异步识别（docs/11 §3.1） */
    public static final String INVOICE_UPLOADED_ROUTING_KEY = "invoice.uploaded";
    public static final String INVOICE_RECOGNIZE_QUEUE = "se.invoice.recognize.queue";
    public static final String INVOICE_DLQ_QUEUE = "se.invoice.recognize.dlq";
    public static final String INVOICE_DLQ_ROUTING_KEY = "invoice.dead";

    public static final String DLX_EXCHANGE = "se.dlx.exchange";
    public static final String DLQ_QUEUE = "se.payment.dlq";
    public static final String DLQ_ROUTING_KEY = "payment.dead";

    @Bean
    public DirectExchange reportExchange() {
        return new DirectExchange(REPORT_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    /** 业务队列：不可重试的消费失败会转投死信交换机 */
    @Bean
    public Queue paymentQueue() {
        return QueueBuilder.durable(REPORT_APPROVED_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue paymentDlq() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    public Binding paymentBinding() {
        return BindingBuilder.bind(paymentQueue()).to(reportExchange()).with(REPORT_APPROVED_ROUTING_KEY);
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(paymentDlq()).to(dlxExchange()).with(DLQ_ROUTING_KEY);
    }

    /** AI 审核队列：消费失败进自己的死信 */
    @Bean
    public Queue aiReviewQueue() {
        return QueueBuilder.durable(AI_REVIEW_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(AI_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue aiReviewDlq() {
        return QueueBuilder.durable(AI_DLQ_QUEUE).build();
    }

    @Bean
    public Binding aiReviewBinding() {
        return BindingBuilder.bind(aiReviewQueue()).to(reportExchange()).with(REPORT_SUBMITTED_ROUTING_KEY);
    }

    @Bean
    public Binding aiReviewDlqBinding() {
        return BindingBuilder.bind(aiReviewDlq()).to(dlxExchange()).with(AI_DLQ_ROUTING_KEY);
    }

    /** 发票识别队列：上传事件 → 识别消费者；消费异常进自己的死信 */
    @Bean
    public Queue invoiceRecognizeQueue() {
        return QueueBuilder.durable(INVOICE_RECOGNIZE_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(INVOICE_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue invoiceDlq() {
        return QueueBuilder.durable(INVOICE_DLQ_QUEUE).build();
    }

    @Bean
    public Binding invoiceRecognizeBinding() {
        return BindingBuilder.bind(invoiceRecognizeQueue()).to(reportExchange()).with(INVOICE_UPLOADED_ROUTING_KEY);
    }

    @Bean
    public Binding invoiceDlqBinding() {
        return BindingBuilder.bind(invoiceDlq()).to(dlxExchange()).with(INVOICE_DLQ_ROUTING_KEY);
    }

    /**
     * Spring Boot 4 的 spring-boot-amqp 自动配置不再注册 RabbitAdmin（只建 ConnectionFactory/Template），
     * 需要自己声明；否则 @Bean 声明的交换机/队列不会被创建。
     */
    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    /**
     * 启动时主动建一次拓扑：没有监听器时 RabbitAdmin 不会自动连 MQ，声明会拖到第一次使用。
     * MQ 未启动时只告警，不让应用起不来（投递调度器后续会重试）。
     */
    @Bean
    public ApplicationRunner rabbitTopologyInitializer(RabbitAdmin rabbitAdmin) {
        return args -> {
            try {
                rabbitAdmin.initialize();
                log.info("RabbitMQ 拓扑已声明：{} → {}，死信 {} → {}",
                        REPORT_EXCHANGE, REPORT_APPROVED_QUEUE, DLX_EXCHANGE, DLQ_QUEUE);
            } catch (Exception e) {
                log.warn("RabbitMQ 拓扑声明失败（MQ 可能未启动），投递时会重试：{}", e.getMessage());
            }
        };
    }
}

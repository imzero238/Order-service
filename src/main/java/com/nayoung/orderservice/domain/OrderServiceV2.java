package com.nayoung.orderservice.domain;

import com.nayoung.orderservice.messagequeue.KStreamKTableJoinConfig;
import com.nayoung.orderservice.messagequeue.KafkaProducerService;
import com.nayoung.orderservice.messagequeue.KafkaProducerConfig;
import com.nayoung.orderservice.openfeign.ItemServiceClient;
import com.nayoung.orderservice.openfeign.ItemUpdateLogDto;
import com.nayoung.orderservice.web.dto.OrderDto;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * KStream(주문에 대한 재고 변경 결과) + KTable(waiting 상태의 주문) Join 한 결과(주문 상세)를 DB에 insert 하는 방식 (v2)
 * 주문 생성을 위해 DB 한 번 접근 (insert)
 */
@Service @Slf4j
public class OrderServiceV2 extends OrderService {

    public OrderServiceV2(OrderRepository orderRepository, OrderRedisRepository orderRedisRepository,
                          KafkaProducerService kafkaProducer,
                          ItemServiceClient itemServiceClient, CircuitBreakerFactory circuitBreakerFactory) {
        super(orderRepository, orderRedisRepository, kafkaProducer, itemServiceClient, circuitBreakerFactory);
    }

    @Override
    @Transactional
    public OrderDto create(OrderDto orderDto) {
        orderDto.setEventId(setEventId(orderDto.getCustomerAccountId()));
        orderDto.initializeRequestedAt();
        orderDto.setOrderStatus(OrderItemStatus.WAITING);
        kafkaProducer.send(KafkaProducerConfig.TEMPORARY_ORDER_TOPIC, orderDto.getEventId(), orderDto);
        return orderDto;
    }

    @KafkaListener(topics = KStreamKTableJoinConfig.FINAL_ORDER_CREATION_TOPIC)
    @Transactional
    public void create(ConsumerRecord<String, OrderDto> record) {
        log.info("Consuming message success -> Topic: {}, Event Id: {}, Order Status: {}",
                record.topic(),
                record.value().getEventId(),
                record.value().getOrderStatus());

        Order order = Order.fromFinalOrderDto(record.value());
        order.getOrderItems()
                .forEach(o -> o.setOrder(order));

        orderRepository.save(order);
    }

    @Override
    @KafkaListener(topics = {KafkaProducerConfig.TEMPORARY_ORDER_TOPIC,
                            KafkaProducerConfig.TEMPORARY_RETRY_ORDER_TOPIC})
    public void checkFinalStatusOfOrder(ConsumerRecord<String, OrderDto> record) {
        log.info("Consuming message success -> Topic: {}, Order Id: {}, Event Id: {}",
                record.topic(),
                record.value().getId(),
                record.value().getEventId());

        try {
            waitBasedOnTimestamp(record.timestamp());

            Optional<Order> order = orderRepository.findByEventId(record.value().getEventId());
            if (order.isEmpty())
                kafkaProducer.send(KafkaProducerConfig.RETRY_REQUEST_ORDER_ITEM_UPDATE_RESULT_TOPIC, record.key(), record.value());
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    @KafkaListener(topics = KafkaProducerConfig.RETRY_REQUEST_ORDER_ITEM_UPDATE_RESULT_TOPIC)
    public void requestOrderItemUpdateResult(ConsumerRecord<String, OrderDto> record) {
        log.info("Consuming message success -> Topic: {}, Key(event Id): {}",
                record.topic(),
                record.key());

        List<ItemUpdateLogDto> itemUpdateLogDtos = getAllOrderItemUpdateResultByEventId(record.key());
        if(!itemUpdateLogDtos.isEmpty())
            kafkaProducer.send(KStreamKTableJoinConfig.ORDER_ITEM_UPDATE_RESULT_TOPIC, record.key(), record.value());
        else resendKafkaMessage(record.key(), record.value());
    }
}

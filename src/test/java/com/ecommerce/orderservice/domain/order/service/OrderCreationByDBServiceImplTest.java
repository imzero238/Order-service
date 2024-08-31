package com.ecommerce.orderservice.domain.order.service;

import com.ecommerce.orderservice.BaseServiceTest;
import com.ecommerce.orderservice.domain.order.OrderStatus;
import com.ecommerce.orderservice.domain.order.dto.OrderDto;
import com.ecommerce.orderservice.domain.order.dto.OrderItemDto;
import com.ecommerce.orderservice.domain.order.dto.OrderRequestDto;
import com.ecommerce.orderservice.domain.order.repository.OrderRepository;;
import com.ecommerce.orderservice.kafka.config.TopicConfig;
import com.ecommerce.orderservice.kafka.dto.OrderKafkaEvent;
import com.ecommerce.orderservice.kafka.service.producer.KafkaProducerService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class OrderCreationByDBServiceImplTest extends BaseServiceTest {

    @Autowired
    OrderCreationByDBServiceImpl orderCreationByDBServiceImpl;

    @Autowired
    OrderInquiryService orderInquiryService;

    @Autowired
    KafkaProducerService kafkaProducerService;

    @Autowired
    OrderRepository orderRepository;

    @AfterEach
    void afterEach() {
        orderRepository.deleteAll();
    }

    @DisplayName("주문 생성 시 주문 상태는 WAITING")
    @Test
    void 요청_주문_생성_테스트() throws InterruptedException {
        // given
        final long accountId = 2L;
        final List<Long> orderItemIds = List.of(2L, 3L, 7L);
        OrderRequestDto orderRequestDto = getOrderRequestDto(accountId, orderItemIds);

        // when
        OrderDto response = orderCreationByDBServiceImpl.create(orderRequestDto);

        // then
        assertThat(response.getId()).isPositive();
        assertThat(response.getAccountId()).isEqualTo(accountId);
        assertThat(response.getOrderStatus()).isEqualTo(OrderStatus.WAITING);

        List<OrderItemDto> orderItemDtos = response.getOrderItemDtos();
        assertThat(orderItemDtos)
                .hasSize(orderItemDtos.size())
                .allMatch(o -> o.getStatus().equals(OrderStatus.WAITING));

        assertThat(orderItemDtos)
                .extracting(OrderItemDto::getItemId)
                .containsExactlyInAnyOrderElementsOf(orderItemIds);
    }

    @DisplayName("주문에 대한 처리 결과 이벤트 수신 후 최종 상태(SUCCEEDED or FAILED)로 update")
    @Test
    void 최종_주문_상태_테스트() throws InterruptedException {
        // given & when
        final long accountId = 2L;
        final List<Long> orderItemIds = List.of(2L, 3L, 7L);
        OrderRequestDto orderRequestDto = getOrderRequestDto(accountId, orderItemIds);
        OrderDto waitingOrder = orderCreationByDBServiceImpl.create(orderRequestDto);

        Thread.sleep(2000);

        final OrderStatus finalOrderStatus = OrderStatus.SUCCEEDED;
        OrderKafkaEvent event = getOrderKafkaEvent(waitingOrder, finalOrderStatus);
        kafkaProducerService.send(TopicConfig.ORDER_PROCESSING_RESULT_TOPIC, waitingOrder.getOrderEventId(), event);

        Thread.sleep(3000);

        // then
        OrderDto finalOrder = orderInquiryService.findLatestOrderByAccountId(accountId);
        assertThat(finalOrder).isNotNull();
        assertThat(finalOrder.getOrderEventId()).isEqualTo(waitingOrder.getOrderEventId());
        assertThat(finalOrder.getOrderItemDtos())
                .hasSize(orderItemIds.size())
                .extracting(OrderItemDto::getItemId)
                .containsExactlyInAnyOrderElementsOf(orderItemIds);

        assertThat(finalOrder.getOrderStatus()).isEqualTo(finalOrderStatus);
        assertThat(finalOrder.getOrderItemDtos())
                .allMatch(orderItemDto -> orderItemDto.getStatus().equals(finalOrderStatus));
    }
}
package com.ecommerce.orderservice.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ExceptionCode {

    NOT_FOUND_ORDER(HttpStatus.BAD_REQUEST, "존재하지 않는 주문입니다."),
    NO_MATCHING_ORDER_STATUS(HttpStatus.NOT_FOUND, "일치하는 주문 상태가 없습니다."),
    NOT_NULL_USER_ID(HttpStatus.BAD_REQUEST, "고객 아이디가 존재하지 않습니다."),
    NOT_FOUND_ORDER_CREATION_INTERNAL_EVENT(HttpStatus.NOT_FOUND, "주문 생성 내부 이벤트가 존재하지 않습니다.");

    private final HttpStatus httpStatus;
    private final String message;
}

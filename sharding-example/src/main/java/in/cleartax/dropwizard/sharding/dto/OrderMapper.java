/*
 * Copyright 2018 Cleartax
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package in.cleartax.dropwizard.sharding.dto;

import in.cleartax.dropwizard.sharding.entities.Order;
import in.cleartax.dropwizard.sharding.entities.OrderItem;

import java.util.stream.Collectors;

public class OrderMapper {
    public OrderItemDto to(OrderItem orderItem) {
        return OrderItemDto.builder()
                .id(orderItem.getId())
                .name(orderItem.getName())
                .build();
    }

    public OrderItem from(OrderItemDto orderItemDto) {
        return OrderItem.builder()
                .id(orderItemDto.getId())
                .name(orderItemDto.getName())
                .build();
    }

    public OrderDto to(Order order) {
        return OrderDto.builder()
                .amount(order.getAmount())
                .id(order.getId())
                .customerId(order.getCustomerId())
                .orderId(order.getOrderId())
                .readOnly(order.isReadonly())
                .items(order.getItems().stream().map(this::to).collect(Collectors.toList()))
                .build();
    }

    public Order from(OrderDto orderDto) {
        return Order.builder()
                .amount(orderDto.getAmount())
                .id(orderDto.getId())
                .customerId(orderDto.getCustomerId())
                .orderId(orderDto.getOrderId())
                .readonly(orderDto.isReadOnly())
                .items(orderDto.getItems().stream().map(this::from).collect(Collectors.toList()))
                .build();
    }

    public Order updateAmount(Order order, OrderDto orderDto) {
        order.setAmount(orderDto.getAmount());
        return order;
    }
}

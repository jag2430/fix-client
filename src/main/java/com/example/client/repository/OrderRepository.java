package com.example.client.repository;

import com.example.client.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<OrderEntity, String> {

    List<OrderEntity> findBySymbol(String symbol);

    List<OrderEntity> findByStatus(String status);

    List<OrderEntity> findBySide(String side);

    @Query("SELECT o FROM OrderEntity o WHERE o.status NOT IN ('FILLED', 'CANCELLED', 'REJECTED', 'REPLACED')")
    List<OrderEntity> findOpenOrders();

    List<OrderEntity> findBySymbolAndSide(String symbol, String side);

    List<OrderEntity> findAllByOrderByTimestampDesc();

    @Query("SELECT o FROM OrderEntity o WHERE o.status NOT IN ('FILLED', 'CANCELLED', 'REJECTED', 'REPLACED') ORDER BY o.timestamp DESC")
    List<OrderEntity> findOpenOrdersOrderByTimestampDesc();
}

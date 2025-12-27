package com.example.client.repository;

import com.example.client.entity.ExecutionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExecutionRepository extends JpaRepository<ExecutionEntity, String> {

    List<ExecutionEntity> findByClOrdId(String clOrdId);

    List<ExecutionEntity> findBySymbol(String symbol);

    List<ExecutionEntity> findByExecType(String execType);

    List<ExecutionEntity> findByOrigClOrdId(String origClOrdId);

    @Query("SELECT e FROM ExecutionEntity e ORDER BY e.timestamp DESC")
    List<ExecutionEntity> findRecentExecutions(Pageable pageable);

    List<ExecutionEntity> findAllByOrderByTimestampDesc();

    @Query("SELECT COUNT(e) FROM ExecutionEntity e")
    int countExecutions();
}

package com.example.toybatch.jpa.basic

import com.example.toybatch.jpa.support.JpaSettlement
import jakarta.persistence.EntityManagerFactory
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.batch.infrastructure.item.database.JpaItemWriter
import org.springframework.orm.jpa.EntityManagerFactoryUtils

/**
 * 정산 저장 + 주문 상태 변경을 "둘 다 writer 에서, 청크 트랜잭션의 EntityManager 로" 한다.
 * 그래서 청크가 롤백되면 둘 다 롤백되고, skip 된 item 은 둘 다 안 남는다.
 */
class SettleAndMarkDoneWriter(
    private val emf: EntityManagerFactory,
    private val settlementWriter: JpaItemWriter<JpaSettlement>,
) : ItemWriter<JpaSettlement> {

    override fun write(chunk: Chunk<out JpaSettlement>) {
        settlementWriter.write(chunk) // merge + flush
        EntityManagerFactoryUtils.getTransactionalEntityManager(emf)!!
            .createQuery("UPDATE JpaOrder o SET o.status = 'DONE' WHERE o.id IN :ids")
            .setParameter("ids", chunk.items.map { it.orderId })
            .executeUpdate()
    }
}

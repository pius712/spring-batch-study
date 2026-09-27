package com.example.toybatch.flow.support

import org.springframework.batch.infrastructure.item.ItemProcessor

/**
 * failAt 번호에서 예외를 던진다 (실패 분기를 보기 위한 장치).
 * @StepScope 빈은 CGLIB 프록시로 감싸지므로 open 이어야 한다.
 */
open class ValidateProcessor(private val failAt: Int?) : ItemProcessor<Int, Int> {

    override fun process(item: Int): Int {
        if (item == failAt) throw IllegalStateException("처리 중 장애: $item")
        return item * 10
    }
}

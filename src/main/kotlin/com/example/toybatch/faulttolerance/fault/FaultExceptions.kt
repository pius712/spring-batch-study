package com.example.toybatch.faulttolerance.fault

/** 일시적인 오류 (외부 API 타임아웃, 락 경합 등). 다시 하면 성공할 수 있다 → retry 대상 */
class TransientException(message: String) : RuntimeException(message)

/** 데이터 자체가 잘못된 오류. 몇 번을 다시 해도 실패한다 → retry 하지 말고 skip 대상 */
class InvalidItemException(val item: Int, message: String) : RuntimeException(message)

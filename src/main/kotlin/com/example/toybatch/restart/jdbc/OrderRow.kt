package com.example.toybatch.restart.jdbc

import org.springframework.jdbc.core.RowMapper

/** rj_order 한 행 */
data class OrderRow(val id: Long, val orderDate: String, val status: String) {

    companion object {
        val ROW_MAPPER = RowMapper { rs, _ ->
            OrderRow(rs.getLong("id"), rs.getString("order_date"), rs.getString("status"))
        }
    }
}

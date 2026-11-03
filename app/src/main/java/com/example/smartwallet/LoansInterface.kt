package com.example.smartwallet

import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Мост JS → LoanCalculator. Деньги передаются строками, чтобы не терять копейки на double.
 * Даты — ISO yyyy-MM-dd. При ошибке ввода возвращается {"error": "..."}.
 */
class LoansInterface {

    private fun bd(v: String) = BigDecimal(v.trim().replace(',', '.'))

    private fun type(v: String) =
        if (v == "diff") LoanCalculator.Type.DIFFERENTIATED else LoanCalculator.Type.ANNUITY

    private inline fun safe(block: () -> Any): String = try {
        block().toString()
    } catch (e: Exception) {
        JSONObject().put("error", e.message ?: "Ошибка расчёта").toString()
    }

    @JavascriptInterface
    fun schedule(principal: String, rate: String, months: Int, issueDate: String, payDay: Int, type: String): String = safe {
        val plan = LoanCalculator.schedule(bd(principal), bd(rate), months, LocalDate.parse(issueDate), payDay, type(type))
        JSONObject()
            .put("payment", plan.first().amount.toPlainString())
            .put("overpayment", LoanCalculator.totalInterest(plan).toPlainString())
            .put("schedule", JSONArray().apply {
                plan.forEach { p ->
                    put(JSONObject()
                        .put("n", p.n)
                        .put("due", p.due.toString())
                        .put("amount", p.amount.toPlainString())
                        .put("principal", p.principalPart.toPlainString())
                        .put("interest", p.interestPart.toPlainString())
                        .put("balance", p.balanceAfter.toPlainString()))
                }
            })
    }

    @JavascriptInterface
    fun scenarios(principal: String, rate: String): String = safe {
        JSONArray().apply {
            LoanCalculator.scenarios(bd(principal), bd(rate)).forEach { s ->
                put(JSONObject()
                    .put("months", s.months)
                    .put("payment", s.monthlyPayment.toPlainString())
                    .put("total", s.total.toPlainString())
                    .put("overpayment", s.overpayment.toPlainString()))
            }
        }
    }

    @JavascriptInterface
    fun prepayForTarget(balance: String, rate: String, monthsLeft: Int, targetPayment: String): String = safe {
        JSONObject().put("amount", LoanCalculator.prepaymentForTargetPayment(bd(balance), bd(rate), monthsLeft, bd(targetPayment)).toPlainString())
    }

    @JavascriptInterface
    fun afterPrepay(balance: String, rate: String, monthsLeft: Int, extra: String): String = safe {
        val p = LoanCalculator.afterPrepayment(bd(balance), bd(rate), monthsLeft, bd(extra))
        JSONObject()
            .put("newPayment", p.newPayment.toPlainString())
            .put("newMonths", p.newMonths)
            .put("savedReducePayment", p.savedReducePayment.toPlainString())
            .put("savedReduceTerm", p.savedReduceTerm.toPlainString())
    }

    @JavascriptInterface
    fun overdue(amountDue: String, principalPart: String, rate: String, penaltyRate: String, due: String, today: String): String = safe {
        val o = LoanCalculator.overdue(bd(amountDue), bd(principalPart), bd(rate), bd(penaltyRate), LocalDate.parse(due), LocalDate.parse(today))
        JSONObject()
            .put("days", o.days)
            .put("interest", o.interest.toPlainString())
            .put("penalty", o.penalty.toPlainString())
            .put("total", o.total.toPlainString())
    }
}

package com.example.smartwallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class LoanCalculatorTest {

    private fun bd(v: String) = BigDecimal(v)

    @Test
    fun annuityMatchesBankCalculators() {
        // Классический пример: 100 000 ₽ под 12% на 12 месяцев
        assertEquals(bd("8884.88"), LoanCalculator.annuityPayment(bd("100000"), bd("12"), 12))
    }

    @Test
    fun zeroRateSplitsEvenly() {
        assertEquals(bd("10000.00"), LoanCalculator.annuityPayment(bd("120000"), bd("0"), 12))
    }

    @Test
    fun annuityScheduleRepaysExactlyThePrincipal() {
        val plan = LoanCalculator.schedule(bd("350000"), bd("19.9"), 24, LocalDate.of(2026, 1, 15), 15)
        assertEquals(24, plan.size)
        val principalSum = plan.fold(BigDecimal.ZERO) { a, p -> a.add(p.principalPart) }
        assertEquals(0, principalSum.compareTo(bd("350000")))
        assertEquals(0, plan.last().balanceAfter.signum())
        // Все платежи, кроме последнего, одинаковые
        assertEquals(1, plan.dropLast(1).map { it.amount }.toSet().size)
    }

    @Test
    fun differentiatedPaymentsDecrease() {
        val plan = LoanCalculator.schedule(
            bd("120000"), bd("12"), 12, LocalDate.of(2026, 1, 10), 10, LoanCalculator.Type.DIFFERENTIATED
        )
        assertEquals(bd("11200.00"), plan.first().amount) // 10 000 долга + 1 200 процентов
        assertTrue(plan.zipWithNext().all { (a, b) -> b.amount < a.amount })
        assertEquals(0, plan.last().balanceAfter.signum())
    }

    @Test
    fun dueDatesClampToShortMonths() {
        val issue = LocalDate.of(2026, 1, 31)
        assertEquals(LocalDate.of(2026, 2, 28), LoanCalculator.dueDate(issue, 31, 1))
        assertEquals(LocalDate.of(2026, 3, 31), LoanCalculator.dueDate(issue, 31, 2))
    }

    @Test
    fun longerTermMeansSmallerPaymentButBiggerOverpayment() {
        val s = LoanCalculator.scenarios(bd("500000"), bd("18"))
        assertEquals(listOf(12, 24, 36), s.map { it.months })
        assertTrue(s[0].monthlyPayment > s[1].monthlyPayment && s[1].monthlyPayment > s[2].monthlyPayment)
        assertTrue(s[0].overpayment < s[1].overpayment && s[1].overpayment < s[2].overpayment)
        s.forEach { assertEquals(0, it.total.compareTo(bd("500000").add(it.overpayment))) }
    }

    @Test
    fun prepaymentToHalvePayment() {
        // Чтобы платёж 8 884,88 стал вдвое меньше, нужно погасить примерно половину долга
        val x = LoanCalculator.prepaymentForTargetPayment(bd("100000"), bd("12"), 12, bd("4442.44"))
        assertTrue("x=$x", (x - bd("50000")).abs() < bd("1"))
        assertEquals(BigDecimal.ZERO.setScale(2), LoanCalculator.prepaymentForTargetPayment(bd("100000"), bd("12"), 12, bd("9000")))
    }

    @Test
    fun prepaymentReducingTermSavesMore() {
        val p = LoanCalculator.afterPrepayment(bd("300000"), bd("20"), 36, bd("100000"))
        assertTrue(p.newPayment < LoanCalculator.annuityPayment(bd("300000"), bd("20"), 36))
        assertTrue(p.newMonths in 1 until 36)
        assertTrue(p.savedReduceTerm > p.savedReducePayment)
        assertTrue(p.savedReducePayment.signum() > 0)
    }

    @Test
    fun overdueAccruesInterestAndPenalty() {
        val due = LocalDate.of(2026, 11, 1)
        val o = LoanCalculator.overdue(bd("10000"), bd("9000"), bd("20"), bd("20"), due, due.plusDays(10))
        assertEquals(10L, o.days)
        assertEquals(bd("49.32"), o.interest)   // 9000 × 20% / 365 × 10
        assertEquals(bd("54.79"), o.penalty)    // 10000 × 20% / 365 × 10
        assertEquals(bd("10104.11"), o.total)
    }

    @Test
    fun notOverdueBeforeDueDate() {
        val due = LocalDate.of(2026, 11, 20)
        val o = LoanCalculator.overdue(bd("10000"), bd("9000"), bd("20"), bd("20"), due, due)
        assertEquals(0L, o.days)
        assertEquals(bd("10000.00"), o.total)
    }
}

package com.example.smartwallet

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Кредитный калькулятор. Все деньги — BigDecimal с округлением до копеек.
 * Проценты считаются помесячно по ставке годовая/12 (как в большинстве банковских калькуляторов).
 */
object LoanCalculator {

    enum class Type { ANNUITY, DIFFERENTIATED }

    data class Payment(
        val n: Int,
        val due: LocalDate,
        val amount: BigDecimal,
        val principalPart: BigDecimal,
        val interestPart: BigDecimal,
        val balanceAfter: BigDecimal
    )

    data class Scenario(
        val months: Int,
        val monthlyPayment: BigDecimal,
        val total: BigDecimal,
        val overpayment: BigDecimal
    )

    data class Prepayment(
        /** Новый платёж, если срок оставить прежним. */
        val newPayment: BigDecimal,
        /** Сколько месяцев останется, если платёж оставить прежним. */
        val newMonths: Int,
        /** Экономия на процентах при уменьшении платежа. */
        val savedReducePayment: BigDecimal,
        /** Экономия на процентах при сокращении срока. */
        val savedReduceTerm: BigDecimal
    )

    data class Overdue(
        val days: Long,
        val interest: BigDecimal,
        val penalty: BigDecimal,
        val total: BigDecimal
    )

    private val MC = MathContext.DECIMAL128
    private val HUNDRED = BigDecimal(100)
    private val TWELVE = BigDecimal(12)
    private val DAYS_IN_YEAR = BigDecimal(365)

    private fun money(v: BigDecimal): BigDecimal = v.setScale(2, RoundingMode.HALF_UP)

    fun monthlyRate(annualRatePct: BigDecimal): BigDecimal =
        annualRatePct.divide(HUNDRED, MC).divide(TWELVE, MC)

    /** Аннуитетный платёж: S·r / (1 − (1+r)^−n). При нулевой ставке — S/n. */
    fun annuityPayment(principal: BigDecimal, annualRatePct: BigDecimal, months: Int): BigDecimal {
        require(months > 0) { "Срок должен быть больше нуля" }
        val r = monthlyRate(annualRatePct)
        if (r.signum() == 0) return money(principal.divide(BigDecimal(months), MC))
        val factor = BigDecimal.ONE.add(r).pow(months, MC)
        // S·r·(1+r)^n / ((1+r)^n − 1) — то же самое без отрицательной степени
        val payment = principal.multiply(r, MC).multiply(factor, MC).divide(factor.subtract(BigDecimal.ONE), MC)
        return money(payment)
    }

    /** Остаток долга, который погашается платежом [payment] за [months] месяцев (обратная формула аннуитета). */
    fun principalForPayment(payment: BigDecimal, annualRatePct: BigDecimal, months: Int): BigDecimal {
        val r = monthlyRate(annualRatePct)
        if (r.signum() == 0) return money(payment.multiply(BigDecimal(months)))
        val factor = BigDecimal.ONE.add(r).pow(months, MC)
        return money(payment.multiply(factor.subtract(BigDecimal.ONE), MC).divide(r.multiply(factor, MC), MC))
    }

    /** Дата n-го платежа: через n месяцев после выдачи, в выбранный день (или последний день короткого месяца). */
    fun dueDate(issueDate: LocalDate, payDay: Int, n: Int): LocalDate {
        val month = issueDate.plusMonths(n.toLong())
        return month.withDayOfMonth(minOf(payDay, month.lengthOfMonth()))
    }

    fun schedule(
        principal: BigDecimal,
        annualRatePct: BigDecimal,
        months: Int,
        issueDate: LocalDate,
        payDay: Int,
        type: Type = Type.ANNUITY
    ): List<Payment> {
        require(principal.signum() > 0) { "Сумма должна быть больше нуля" }
        require(annualRatePct.signum() >= 0) { "Ставка не может быть отрицательной" }
        val r = monthlyRate(annualRatePct)
        val annuity = if (type == Type.ANNUITY) annuityPayment(principal, annualRatePct, months) else BigDecimal.ZERO
        val fixedPrincipal = if (type == Type.DIFFERENTIATED) principal.divide(BigDecimal(months), MC) else BigDecimal.ZERO

        val result = ArrayList<Payment>(months)
        var balance = principal
        for (n in 1..months) {
            val interest = money(balance.multiply(r, MC))
            var principalPart = if (type == Type.ANNUITY) annuity.subtract(interest) else money(fixedPrincipal)
            // Последний платёж закрывает остаток целиком, без копеечных хвостов от округления
            if (n == months || principalPart > balance) principalPart = balance
            val amount = principalPart.add(interest)
            balance = balance.subtract(principalPart)
            result.add(Payment(n, dueDate(issueDate, payDay, n), amount, principalPart, interest, balance))
        }
        return result
    }

    fun totalInterest(schedule: List<Payment>): BigDecimal =
        schedule.fold(BigDecimal.ZERO) { acc, p -> acc.add(p.interestPart) }

    /** Сценарии погашения за 1, 2 и 3 года (или другие сроки в месяцах). */
    fun scenarios(principal: BigDecimal, annualRatePct: BigDecimal, monthsList: List<Int> = listOf(12, 24, 36)): List<Scenario> =
        monthsList.map { months ->
            val plan = schedule(principal, annualRatePct, months, LocalDate.of(2000, 1, 1), 1)
            val interest = totalInterest(plan)
            Scenario(months, plan.first().amount, principal.add(interest), interest)
        }

    /** Сколько внести досрочно, чтобы платёж стал [targetPayment] при том же оставшемся сроке: X = B − B'. */
    fun prepaymentForTargetPayment(
        balance: BigDecimal,
        annualRatePct: BigDecimal,
        monthsLeft: Int,
        targetPayment: BigDecimal
    ): BigDecimal {
        val affordableBalance = principalForPayment(targetPayment, annualRatePct, monthsLeft)
        return money(balance.subtract(affordableBalance).max(BigDecimal.ZERO))
    }

    /** Что даст досрочный взнос [extra]: новый платёж или новый срок и экономия в каждом варианте. */
    fun afterPrepayment(
        balance: BigDecimal,
        annualRatePct: BigDecimal,
        monthsLeft: Int,
        extra: BigDecimal
    ): Prepayment {
        val r = monthlyRate(annualRatePct)
        val currentPayment = annuityPayment(balance, annualRatePct, monthsLeft)
        val interestBefore = currentPayment.multiply(BigDecimal(monthsLeft)).subtract(balance)

        val rest = balance.subtract(extra)
        if (rest.signum() <= 0) {
            return Prepayment(BigDecimal.ZERO.setScale(2), 0, money(interestBefore), money(interestBefore))
        }

        val newPayment = annuityPayment(rest, annualRatePct, monthsLeft)
        val savedReducePayment = interestBefore.subtract(newPayment.multiply(BigDecimal(monthsLeft)).subtract(rest))

        // Срок при прежнем платеже: считаем помесячно, пока остаток не закроется
        var left = rest
        var months = 0
        var interestPaid = BigDecimal.ZERO
        while (left.signum() > 0 && months < monthsLeft) {
            val interest = money(left.multiply(r, MC))
            interestPaid = interestPaid.add(interest)
            left = left.subtract(currentPayment.subtract(interest))
            months++
        }
        return Prepayment(newPayment, months, money(savedReducePayment), money(interestBefore.subtract(interestPaid)))
    }

    /**
     * Просрочка платежа: проценты продолжают идти на просроченный основной долг,
     * плюс неустойка на всю сумму платежа по ставке из договора (годовых).
     */
    fun overdue(
        amountDue: BigDecimal,
        principalPart: BigDecimal,
        annualRatePct: BigDecimal,
        penaltyRatePct: BigDecimal,
        due: LocalDate,
        today: LocalDate
    ): Overdue {
        val days = ChronoUnit.DAYS.between(due, today)
        if (days <= 0) return Overdue(0, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), money(amountDue))
        val dayCount = BigDecimal(days)
        val interest = money(principalPart.multiply(annualRatePct, MC).divide(HUNDRED, MC).divide(DAYS_IN_YEAR, MC).multiply(dayCount, MC))
        val penalty = money(amountDue.multiply(penaltyRatePct, MC).divide(HUNDRED, MC).divide(DAYS_IN_YEAR, MC).multiply(dayCount, MC))
        return Overdue(days, interest, penalty, money(amountDue.add(interest).add(penalty)))
    }
}

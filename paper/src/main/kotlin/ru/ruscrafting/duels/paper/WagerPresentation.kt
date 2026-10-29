package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import ru.ruscrafting.duels.domain.MAX_DUEL_STAKE_MINOR
import java.math.BigDecimal

/** Player amounts are decimal coins; the domain and persistence use hundredths of a coin. */
internal fun wagerAmountComponent(amountMinor: Long): Component =
    Component.text(BigDecimal.valueOf(amountMinor, 2).stripTrailingZeros().toPlainString())
        .append(Component.text(" "))
        .append(Component.text("💰", NamedTextColor.WHITE))

/** A plain decimal amount only: no exponent, sign, grouping, rounding or floating point. */
internal fun parseDuelStakeMinor(input: String): Long? {
    val value = input.trim().replace(',', '.')
    if (!DUEL_STAKE_DECIMAL.matches(value)) return null
    val amount = value.toBigDecimalOrNull() ?: return null
    if (amount > BigDecimal.valueOf(MAX_DUEL_STAKE_MINOR, 2)) return null
    return amount.movePointRight(2).longValueExact()
}

private val DUEL_STAKE_DECIMAL = Regex("[0-9]{1,16}(\\.[0-9]{1,2})?")

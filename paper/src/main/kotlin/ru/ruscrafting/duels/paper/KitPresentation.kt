package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

internal fun DuelKit.localizedName(player: Player, locales: LocaleService): Component {
    val key = "kit.${id.value}.name"
    return if (locales.hasKey(locales.language(player), key)) locales.component(player, key) else displayName
}

/** Builds the visible kit manifest from the exact runtime stacks to prevent config/lore drift. */
internal fun DuelKit.contentLore(player: Player, locales: LocaleService): List<Component> =
    buildList {
        add(Component.empty())
        add(locales.component(player, "menu.kits.contents"))
        contents().forEach { stack -> add(stack.contentLine(player, locales)) }
    }

private fun DuelKit.contents(): List<ItemStack> =
    buildList {
        items.toSortedMap().values.forEach { add(it) }
        offhand?.let(::add)
        helmet?.let(::add)
        chestplate?.let(::add)
        leggings?.let(::add)
        boots?.let(::add)
    }

private fun ItemStack.contentLine(player: Player, locales: LocaleService): Component {
    var line = Component.text("• ", NamedTextColor.DARK_GRAY)
        .append(Component.translatable(type.translationKey()).color(NamedTextColor.GRAY))
    if (amount > 1) line = line.append(Component.text(" ×$amount", NamedTextColor.WHITE))
    if (enchantments.isNotEmpty()) {
        line = line.append(Component.text("  ", NamedTextColor.DARK_GRAY))
        enchantments.entries.sortedBy { it.key.key.asString() }.forEachIndexed { index, (enchantment, level) ->
            if (index > 0) line = line.append(Component.text(", ", NamedTextColor.DARK_GRAY))
            line = line.append(enchantment.displayName(level).color(NamedTextColor.DARK_AQUA))
        }
    }
    if (itemMeta.isUnbreakable) {
        line = line.append(Component.text("  ·  ", NamedTextColor.DARK_GRAY))
            .append(locales.component(player, "menu.kits.unbreakable"))
    }
    return line
}

package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration

class GuiItemCatalogTest : StringSpec({
    "localized item keys can override fallback materials through normalized roles" {
        val configuration = YamlConfiguration()
        configuration.set("gui.items.menu-main-challenge.material", "DIAMOND_SWORD")
        val catalog = GuiItemCatalog.load(configuration)

        catalog.create("menu.main.challenge", Material.WOODEN_SWORD).type shouldBe Material.DIAMOND_SWORD
        catalog.create("menu.main.unknown", Material.STONE).type shouldBe Material.STONE
    }
})

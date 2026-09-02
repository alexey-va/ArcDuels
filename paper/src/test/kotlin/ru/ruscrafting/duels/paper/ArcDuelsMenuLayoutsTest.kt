package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files

class ArcDuelsMenuLayoutsTest : StringSpec({
    "every bundled duel screen has a complete validated slot mapping" {
        val layouts = ArcDuelsMenuLayouts.loadResource(javaClass.classLoader)

        ArcDuelsMenuScreen.entries.forEach { screen ->
            layouts.rows(screen) shouldBe 5
            (0 until 45).forEach { logical ->
                layouts.physical(screen, logical) shouldBe logical
                layouts.logical(screen, logical) shouldBe logical
            }
            layouts.logical(screen, 45) shouldBe null
        }
    }

    "configured permutation moves rendered items and reverses click slots" {
        val root = Files.createTempDirectory("arc-duels-menu-permutation")
        val source = requireNotNull(javaClass.classLoader.getResource("gui-layouts.yml")).readText()
        Files.writeString(
            root.resolve("gui-layouts.yml"),
            source.replace("['0-44']", "[44, 43, 42, 41, 40, 39, 38, 37, 36, 35, 34, 33, 32, 31, 30, 29, 28, 27, 26, 25, 24, 23, 22, 21, 20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0]"),
        )
        val layouts = ArcDuelsMenuLayouts.load(root)
        val paper = MockBukkitTestRuntime.open()
        try {
            val inventory = paper.server.createInventory(null, 45)
            inventory.setItem(0, ItemStack(Material.STONE))
            inventory.setItem(44, ItemStack(Material.DIAMOND))

            layouts.arrange(ArcDuelsMenuScreen.MAIN, inventory)

            inventory.getItem(44)?.type shouldBe Material.STONE
            inventory.getItem(0)?.type shouldBe Material.DIAMOND
            layouts.logical(ArcDuelsMenuScreen.MAIN, 44) shouldBe 0
            layouts.logical(ArcDuelsMenuScreen.MAIN, 0) shouldBe 44
        } finally {
            paper.close()
        }
    }
})

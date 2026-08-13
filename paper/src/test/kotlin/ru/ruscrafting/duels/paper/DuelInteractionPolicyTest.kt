package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.KitId

class DuelInteractionPolicyTest : StringSpec({
    "only chat and safe duel commands pass while fighting" {
        val policy = DuelCommandPolicy()

        policy.isAllowed("/msg Alex привет") shouldBe true
        policy.isAllowed("/minecraft:tell Alex привет") shouldBe true
        policy.isAllowed("/duel leave") shouldBe true
        policy.isAllowed("/duels stats") shouldBe true
        policy.isAllowed("/duels top") shouldBe false
        policy.isAllowed("/spawn") shouldBe false
        policy.isAllowed("/duel Alex") shouldBe false
    }

    "kit menu exposes casual and ranked BO1 or BO3 without ranking own inventory" {
        val kit = DuelRules(DuelMode.KIT, KitId("classic"))
        val own = DuelRules(DuelMode.OWN_INVENTORY)

        rulesForSelection(kit, shiftClick = false, rightClick = false) shouldBe kit
        rulesForSelection(kit, shiftClick = true, rightClick = true) shouldBe
            kit.copy(ranked = true, bestOf = 3)
        rulesForSelection(own, shiftClick = true, rightClick = true) shouldBe
            own.copy(bestOf = 3)
    }

    "pagination clamps stale pages and never drops the final partial page" {
        val items = (1..101).toList()

        pageWindow(items, requestedPage = 0, pageSize = 45).items shouldBe (1..45).toList()
        pageWindow(items, requestedPage = 2, pageSize = 45) shouldBe
            PageWindow(index = 2, totalPages = 3, items = (91..101).toList())
        pageWindow(items, requestedPage = 99, pageSize = 45).index shouldBe 2
        pageWindow(emptyList<Int>(), requestedPage = 4, pageSize = 45) shouldBe
            PageWindow(index = 0, totalPages = 1, items = emptyList())
    }
})
